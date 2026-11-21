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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final byte[] enrollmentKey;
    private final SecureRandom random = new SecureRandom();

    SharingService(MemberMapper members, InvitationMapper invitations, PositionSampleMapper samples, WriteGuard guard,
                   PasswordEncoder passwords, JdbcTemplate jdbc, @Value("${tongxing.enrollment-key}") String enrollmentKey) {
        this.members = members;
        this.invitations = invitations;
        this.samples = samples;
        this.guard = guard;
        this.passwords = passwords;
        this.jdbc = jdbc;
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
        jdbc.update("DELETE FROM companion_event WHERE pair_id = ?", self.pairId);
    }

    @Transactional
    public ShareState consent(String username, boolean enabled) {
        guard.lock();
        Member self = current(username);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        String shareKey = enabled ? id() : null;
        members.update(null, new UpdateWrapper<Member>().eq("id", self.id)
                .set("sharing", enabled).set("share_key", shareKey));
        jdbc.update("UPDATE shared_place SET inside = NULL WHERE member_id = ?", self.id);
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
        if (self.usageEnabled && reading.screenMinutes() != null && reading.screenDate() != null) {
            jdbc.update("""
                    UPDATE member SET screen_minutes = ?, screen_date = ?
                    WHERE id = ? AND (screen_date IS NULL OR screen_date < ?
                    OR (screen_date = ? AND screen_minutes <= ?))
                    """, reading.screenMinutes(), reading.screenDate(), self.id,
                    reading.screenDate(), reading.screenDate(), reading.screenMinutes());
        }
        if (now - sample.recordedAt <= Duration.ofMinutes(3).toMillis()) observePlaces(self, sample);
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
        jdbc.update("DELETE FROM companion_event WHERE sender_id = ? AND kind IN ('ARRIVE', 'LEAVE')", self.id);
        jdbc.update("UPDATE shared_place SET inside = NULL, observed_at = NULL WHERE member_id = ?", self.id);
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void expireOldData() {
        guard.lock();
        long now = System.currentTimeMillis();
        samples.delete(new QueryWrapper<PositionSample>().lt("recorded_at", now - RETENTION));
        invitations.delete(new QueryWrapper<Invitation>().le("expires_at", now));
        jdbc.update("DELETE FROM companion_event WHERE created_at < ?", now - RETENTION);
        jdbc.update("DELETE FROM push_device WHERE refreshed_at < ?", now - RETENTION);
    }

    @Transactional
    public Feed feed(String username, Long before) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) return new Feed(null, List.of());
        List<Event> events = jdbc.query("""
                SELECT e.id, e.kind, e.body, m.display_name, e.sender_id, e.created_at
                FROM companion_event e JOIN member m ON e.sender_id = m.id
                WHERE e.pair_id = ? AND e.id < ? AND e.created_at >= ?
                ORDER BY e.id DESC LIMIT 80
                """, (row, i) -> new Event(row.getLong("id"), row.getString("kind"), row.getString("body"),
                row.getString("display_name"), !self.id.equals(row.getString("sender_id")), row.getLong("created_at")),
                self.pairId, before == null ? Long.MAX_VALUE : before, System.currentTimeMillis() - RETENTION);
        return new Feed(self.pairId, events);
    }

    @Transactional
    public void interact(String username, String kind, String body) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        long now = System.currentTimeMillis();
        long window = kind.equals("MESSAGE") ? 1000 : 15_000;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companion_event WHERE pair_id = ? AND sender_id = ?
                AND kind = ? AND created_at >= ?
                """, Integer.class, self.pairId, self.id, kind, now - window);
        if (count != null && count > 0) throw failure(HttpStatus.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
        addEvent(self, kind, body.strip(), now);
    }

    private void addEvent(Member self, String kind, String body, long time) {
        jdbc.update("INSERT INTO companion_event(pair_id, sender_id, kind, body, created_at) VALUES (?, ?, ?, ?, ?)",
                self.pairId, self.id, kind, body, time);
    }

    @Transactional
    public List<Place> places(String username) {
        guard.lock();
        return ownPlaces(current(username).id);
    }

    private List<Place> ownPlaces(String memberId) {
        return jdbc.query("""
                SELECT id, name, latitude, longitude, radius, inside, observed_at
                FROM shared_place WHERE member_id = ? ORDER BY name, id
                """, (row, i) -> new Place(row.getString("id"), row.getString("name"),
                row.getDouble("latitude"), row.getDouble("longitude"), row.getInt("radius"),
                row.getObject("inside", Boolean.class), row.getObject("observed_at", Long.class)), memberId);
    }

    @Transactional
    public void addPlace(String username, PlaceInput place) {
        guard.lock();
        Member self = current(username);
        if (ownPlaces(self.id).size() >= 12) throw failure(HttpStatus.CONFLICT, "最多可保存 12 个地点");
        jdbc.update("INSERT INTO shared_place(id, member_id, name, latitude, longitude, radius) VALUES (?, ?, ?, ?, ?, ?)",
                id(), self.id, place.name().strip(), place.latitude(), place.longitude(), place.radius());
    }

    @Transactional
    public void removePlace(String username, UUID id) {
        guard.lock();
        jdbc.update("DELETE FROM shared_place WHERE id = ? AND member_id = ?", id.toString(), current(username).id);
    }

    private void observePlaces(Member self, PositionSample sample) {
        for (Place place : ownPlaces(self.id)) {
            if (sample.accuracy > place.radius() / 2.0
                    || (place.observedAt() != null && sample.recordedAt <= place.observedAt())) continue;
            double distance = distanceMeters(place.latitude(), place.longitude(), sample.latitude, sample.longitude);
            double margin = Math.max(25, sample.accuracy);
            if (Math.abs(distance - place.radius()) <= margin) continue;
            boolean inside = distance < place.radius();
            jdbc.update("UPDATE shared_place SET inside = ?, observed_at = ? WHERE id = ?", inside,
                    sample.recordedAt, place.id());
            if (place.inside() != null && inside != place.inside()) {
                addEvent(self, inside ? "ARRIVE" : "LEAVE", place.name(), sample.recordedAt);
            }
        }
    }

    private static double distanceMeters(double firstLat, double firstLon, double lat, double lon) {
        double a = Math.pow(Math.sin(Math.toRadians(lat - firstLat) / 2), 2)
                + Math.cos(Math.toRadians(firstLat)) * Math.cos(Math.toRadians(lat))
                * Math.pow(Math.sin(Math.toRadians(lon - firstLon) / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    @Transactional
    public void usageSharing(String username, boolean enabled) {
        guard.lock();
        Member self = current(username);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        jdbc.update("UPDATE member SET usage_enabled = ?, screen_minutes = NULL, screen_date = NULL WHERE id = ?",
                enabled, self.id);
    }

    @Transactional
    public void pushToken(String username, String token, boolean enabled) {
        guard.lock();
        Member self = current(username);
        if (!enabled) {
            jdbc.update("DELETE FROM push_device WHERE token = ? AND member_id = ?", token, self.id);
            return;
        }
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE push_device SET refreshed_at = ? WHERE token = ? AND member_id = ?",
                now, token, self.id);
        if (updated != 0) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM push_device WHERE member_id = ?", Integer.class, self.id);
        if (count != null && count >= 5) throw failure(HttpStatus.CONFLICT, "已达到设备数量上限");
        jdbc.update("DELETE FROM push_device WHERE token = ?", token);
        long watermark = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM companion_event", Long.class);
        jdbc.update("INSERT INTO push_device(token, member_id, delivered_through, refreshed_at) VALUES (?, ?, ?, ?)",
                token, self.id, watermark, now);
    }

    @Transactional
    public void deleteAccount(String username, String password) {
        Member member = current(username);
        if (!passwords.matches(password, member.passwordHash)) throw failure(HttpStatus.FORBIDDEN, "密码不正确");
        unpair(username);
        jdbc.update("DELETE FROM companion_event WHERE sender_id = ?", member.id);
        jdbc.update("DELETE FROM shared_place WHERE member_id = ?", member.id);
        samples.delete(new QueryWrapper<PositionSample>().eq("member_id", member.id));
        jdbc.update("DELETE FROM push_device WHERE member_id = ?", member.id);
        members.deleteById(member.id);
    }

    private Status status(Member member, long now) {
        if (!member.sharing || member.pairId == null) return new Status(member.displayName, false, null, null, null, member.usageEnabled);
        List<PositionSample> latest = samples.selectList(query(member).ge("recorded_at", now - RETENTION)
                .orderByDesc("recorded_at", "id").last("LIMIT 1"));
        return new Status(member.displayName, true, latest.isEmpty() ? null : point(latest.getFirst()),
                member.usageEnabled ? member.screenMinutes : null,
                member.usageEnabled ? member.screenDate : null, member.usageEnabled);
    }

    private QueryWrapper<PositionSample> query(Member member) {
        return new QueryWrapper<PositionSample>().eq("pair_id", member.pairId).eq("member_id", member.id);
    }

    private void setPair(Member member, String pairId) {
        members.update(null, new UpdateWrapper<Member>().eq("id", member.id).set("pair_id", pairId)
                .set("sharing", false).set("share_key", null)
                .set("usage_enabled", false).set("screen_minutes", null).set("screen_date", null));
        jdbc.update("UPDATE shared_place SET inside = NULL, observed_at = NULL WHERE member_id = ?", member.id);
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
