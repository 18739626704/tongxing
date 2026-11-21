package app.tongxing;

import app.tongxing.SharingController.*;
import app.tongxing.store.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class SharingService {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long RETENTION = 30 * DAY;
    private static final long INVITATION_LIFETIME = Duration.ofMinutes(15).toMillis();

    private final MemberMapper members;
    private final InvitationMapper invitations;
    private final PositionSampleMapper samples;
    private final WriteGuard guard;
    private final PasswordEncoder passwords;
    private final byte[] enrollmentKey;
    private final SecureRandom random = new SecureRandom();

    SharingService(MemberMapper members, InvitationMapper invitations, PositionSampleMapper samples, WriteGuard guard,
                   PasswordEncoder passwords, @Value("${tongxing.enrollment-key}") String enrollmentKey) {
        this.members = members;
        this.invitations = invitations;
        this.samples = samples;
        this.guard = guard;
        this.passwords = passwords;
        this.enrollmentKey = enrollmentKey.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public void register(Registration registration) {
        if (enrollmentKey.length < 24 || !MessageDigest.isEqual(enrollmentKey,
                registration.enrollmentKey().getBytes(StandardCharsets.UTF_8))) {
            throw failure(HttpStatus.FORBIDDEN, "注册口令无效");
        }
        if (registration.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw failure(HttpStatus.BAD_REQUEST, "密码太长，请使用 10–64 个英文字母、数字或符号");
        }
        String passwordHash = passwords.encode(registration.password());
        guard.lock();
        if (findMember(registration.username()) != null) {
            throw failure(HttpStatus.CONFLICT, "账号已存在");
        }
        Member member = new Member();
        member.id = id();
        member.username = registration.username();
        member.displayName = registration.displayName().strip();
        member.passwordHash = passwordHash;
        members.insert(member);
    }

    @Transactional
    public InviteCode invite(String username) {
        guard.lock();
        Member self = requireUnpaired(current(username));
        invitations.delete(new QueryWrapper<Invitation>().eq("member_id", self.id));
        byte[] secret = new byte[8];
        random.nextBytes(secret);
        Invitation invitation = new Invitation();
        invitation.id = HexFormat.of().withUpperCase().formatHex(secret);
        invitation.memberId = self.id;
        invitation.expiresAt = System.currentTimeMillis() + INVITATION_LIFETIME;
        invitations.insert(invitation);
        return new InviteCode(invitation.id, invitation.expiresAt);
    }

    @Transactional
    public void pair(String username, String code) {
        guard.lock();
        Member self = requireUnpaired(current(username));
        Invitation invitation = invitations.selectById(code.toUpperCase(Locale.ROOT));
        if (invitation == null || invitation.expiresAt <= System.currentTimeMillis()
                || invitation.memberId.equals(self.id)) {
            throw failure(HttpStatus.CONFLICT, "邀请码无效、已使用或已过期");
        }
        Member partner = requireUnpaired(members.selectById(invitation.memberId));
        String pairId = id();
        setPair(self, pairId);
        setPair(partner, pairId);
        invitations.delete(new QueryWrapper<Invitation>().in("member_id", self.id, partner.id));
    }

    @Transactional
    public void unpair(String username) {
        guard.lock();
        Member self = current(username);
        invitations.delete(new QueryWrapper<Invitation>().eq("member_id", self.id));
        if (self.pairId == null) return;
        Member partner = partnerOf(self);
        if (partner != null) setPair(partner, null);
        setPair(self, null);
        samples.delete(new QueryWrapper<PositionSample>().eq("pair_id", self.pairId));
    }

    @Transactional
    public ShareState consent(String username, boolean enabled) {
        guard.lock();
        Member self = current(username);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        String shareKey = enabled ? id() : null;
        members.update(null, new UpdateWrapper<Member>().eq("id", self.id)
                .set("sharing", enabled).set("share_key", shareKey));
        return new ShareState(shareKey);
    }

    @Transactional
    public void record(String username, Reading reading) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null || !self.sharing || !reading.shareKey().toString().equals(self.shareKey)) {
            throw failure(HttpStatus.CONFLICT, "共享已暂停或本次共享已结束");
        }
        long now = System.currentTimeMillis();
        if (reading.recordedAt() < now - DAY || reading.recordedAt() > now + Duration.ofMinutes(2).toMillis()) {
            throw failure(HttpStatus.BAD_REQUEST, "定位时间无效，请检查手机时钟");
        }
        if (samples.selectById(reading.id().toString()) != null) return;
        PositionSample sample = new PositionSample();
        sample.id = reading.id().toString();
        sample.memberId = self.id;
        sample.pairId = self.pairId;
        sample.latitude = reading.latitude();
        sample.longitude = reading.longitude();
        sample.accuracy = reading.accuracy();
        sample.battery = reading.battery();
        sample.charging = reading.charging();
        sample.recordedAt = reading.recordedAt();
        samples.insert(sample);
    }

    @Transactional
    public Overview overview(String username) {
        guard.lock();
        Member self = current(username);
        Member partner = partnerOf(self);
        long now = System.currentTimeMillis();
        return new Overview(status(self, now), partner == null ? null : status(partner, now), now);
    }

    @Transactional
    public List<Point> route(String username, boolean partner, long from, long to) {
        long now = System.currentTimeMillis();
        if (from < 0 || from >= to || to - from > 25 * 60 * 60 * 1000L) {
            throw failure(HttpStatus.BAD_REQUEST, "一次最多查看一天的轨迹");
        }
        guard.lock();
        Member self = current(username);
        Member target = partner ? partnerOf(self) : self;
        if (target == null || target.pairId == null || (partner && !target.sharing)) return List.of();
        List<PositionSample> route = samples.selectList(query(target)
                .ge("recorded_at", Math.max(from, now - RETENTION)).lt("recorded_at", to)
                .orderByDesc("recorded_at", "id").last("LIMIT 5000"));
        Collections.reverse(route);
        return route.stream().map(SharingService::point).toList();
    }

    @Transactional
    public void clear(String username) {
        guard.lock();
        Member self = current(username);
        members.update(null, new UpdateWrapper<Member>().eq("id", self.id)
                .set("sharing", false).set("share_key", null));
        samples.delete(new QueryWrapper<PositionSample>().eq("member_id", self.id));
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void expireOldData() {
        guard.lock();
        long now = System.currentTimeMillis();
        samples.delete(new QueryWrapper<PositionSample>().lt("recorded_at", now - RETENTION));
        invitations.delete(new QueryWrapper<Invitation>().le("expires_at", now));
    }

    private Status status(Member member, long now) {
        if (!member.sharing || member.pairId == null) return new Status(member.displayName, false, null);
        List<PositionSample> latest = samples.selectList(query(member).ge("recorded_at", now - RETENTION)
                .orderByDesc("recorded_at", "id").last("LIMIT 1"));
        return new Status(member.displayName, true, latest.isEmpty() ? null : point(latest.getFirst()));
    }

    private QueryWrapper<PositionSample> query(Member member) {
        return new QueryWrapper<PositionSample>().eq("pair_id", member.pairId).eq("member_id", member.id);
    }

    private void setPair(Member member, String pairId) {
        members.update(null, new UpdateWrapper<Member>().eq("id", member.id).set("pair_id", pairId)
                .set("sharing", false).set("share_key", null));
    }

    private Member findMember(String username) {
        return members.selectOne(new QueryWrapper<Member>().eq("username", username));
    }

    private Member current(String username) {
        Member member = findMember(username);
        if (member == null) throw failure(HttpStatus.UNAUTHORIZED, "请重新登录");
        return member;
    }

    private Member partnerOf(Member self) {
        if (self.pairId == null) return null;
        return members.selectOne(new QueryWrapper<Member>().eq("pair_id", self.pairId).ne("id", self.id));
    }

    private static Member requireUnpaired(Member member) {
        if (member == null || member.pairId != null) throw failure(HttpStatus.CONFLICT, "已有绑定关系");
        return member;
    }

    private static Point point(PositionSample sample) {
        return new Point(sample.latitude, sample.longitude, sample.accuracy,
                sample.battery, sample.charging, sample.recordedAt);
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
