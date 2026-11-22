package app.tongxing;

import app.tongxing.SharingController.*;
import app.tongxing.AdminController.*;
import static app.tongxing.AdminController.ACCOUNT_PAGE_SIZE;
import app.tongxing.store.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.nio.charset.StandardCharsets;
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
    private final SavedPlaces places;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final PasswordEncoder passwords;
    private final AmapPlaceNames placeNames;
    private final String amapAndroidKey;
    private final boolean registrationEnabled;
    private final SecureRandom random = new SecureRandom();

    SharingService(MemberMapper members, InvitationMapper invitations, PositionSampleMapper samples, WriteGuard guard,
                   PasswordEncoder passwords, JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper json,
                   AmapPlaceNames placeNames, SavedPlaces places, @Value("${AMAP_ANDROID_KEY:}") String amapAndroidKey,
                   @Value("${tongxing.registration-enabled}") boolean registrationEnabled) {
        this.members = members;
        this.invitations = invitations;
        this.samples = samples;
        this.guard = guard;
        this.passwords = passwords;
        this.placeNames = placeNames;
        this.places = places;
        String mapKey = amapAndroidKey.strip();
        this.amapAndroidKey = mapKey.matches("[0-9a-fA-F]{32}") ? mapKey : null;
        this.jdbc = jdbc;
        this.json = json;
        this.registrationEnabled = registrationEnabled;
    }

    @Transactional
    public void register(Registration registration) {
        if (!registrationEnabled) {
            throw failure(HttpStatus.FORBIDDEN, "服务器已停止新用户注册");
        }
        if (registration.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw failure(HttpStatus.BAD_REQUEST, "密码超过 72 字节，请减少中文、表情等多字节字符");
        }
        String passwordHash = passwords.encode(registration.password());
        guard.lock();
        if (findMember(registration.username()) != null) {
            throw failure(HttpStatus.CONFLICT, "账号已存在");
        }
        Member member = new Member();
        member.id = id();
        member.username = registration.username();
        String displayName = registration.displayName();
        member.displayName = displayName == null || displayName.isBlank()
                ? registration.username() : displayName.strip();
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
        jdbc.update("DELETE FROM companion_place WHERE pair_id = ?", self.pairId);
    }

    @Transactional
    public ShareState consent(String username, boolean enabled, UUID pairId, boolean locationUnavailable) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        if (enabled && pairId != null && self.sharing && self.shareKey != null) {
            return new ShareState(self.shareKey);
        }
        String shareKey = enabled ? id() : null;
        members.update(null, new UpdateWrapper<Member>().eq("id", self.id)
                .set("sharing", enabled).set("share_key", shareKey)
                .set("location_unavailable", !enabled && locationUnavailable));
        if (!enabled) clearSharedStatuses(self);
        return new ShareState(shareKey);
    }

    private void clearSharedStatuses(Member member) {
        clearInsights(member.id);
        jdbc.update("UPDATE companion_place SET watching = FALSE, armed = FALSE, inside_at = NULL WHERE pair_id = ? AND owner_id <> ?",
                member.pairId, member.id);
        jdbc.update("UPDATE companion_event SET sos_sample_id = NULL WHERE sender_id = ? AND sos_sample_id IS NOT NULL", member.id);
    }

    private void checkPair(Member self, UUID pairId) {
        if (pairId != null && !pairId.toString().equals(self.pairId)) {
            throw failure(HttpStatus.CONFLICT, "伴侣绑定已更换，请重新确认共享");
        }
    }

    @Transactional
    public ShareState resumeSharing(String username) {
        guard.lock();
        Member member = current(username);
        if (!member.sharing || member.pairId == null || member.shareKey == null) {
            throw failure(HttpStatus.CONFLICT, "位置共享已暂停");
        }
        return new ShareState(member.shareKey);
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
        PositionSample previous = self.deviceStatusEnabled ? lastPoint(self, now) : null;
        samples.insert(sample);
        if (self.deviceStatusEnabled && (reading.networkType() != null || reading.deviceModel() != null) && now - sample.recordedAt <= 180_000
                && (self.deviceStatusAt == null || sample.recordedAt > self.deviceStatusAt)) {
            recordDeviceStatus(self, reading, sample, previous, now);
            notifyLowBattery(self, sample, previous, now);
        }
        if (self.usageEnabled && reading.screenMinutes() != null && reading.screenDate() != null) {
            jdbc.update("""
                    UPDATE member SET screen_minutes = ?, screen_date = ?
                    WHERE id = ? AND (screen_date IS NULL OR screen_date < ?
                    OR (screen_date = ? AND screen_minutes <= ?))
                    """, reading.screenMinutes(), reading.screenDate(), self.id,
                    reading.screenDate(), reading.screenDate(), reading.screenMinutes());
        }
        if (now - sample.recordedAt <= 120_000) {
            if (self.journeyEnabled) recordJourney(self, sample, Boolean.TRUE.equals(reading.amapPlaceNames()));
            places.recordArrivals(self.pairId, self.id, sample,
                    name -> addEvent(self, "PLACE_ARRIVAL", "到达 " + name, sample.recordedAt));
        }
    }

    @Transactional
    public Overview overview(String username) {
        guard.lock();
        Member self = current(username);
        Member partner = partnerOf(self);
        long now = System.currentTimeMillis();
        return new Overview(status(self, now), partner == null ? null : status(partner, now), now, self.pairId,
                new MapConfig(amapAndroidKey, placeNames.configured()));
    }

    public List<Account> adminAccounts(String search, String after) {
        QueryWrapper<Member> query = new QueryWrapper<Member>()
                .select("id", "username", "display_name", "pair_id", "disabled", "sharing");
        if (!search.isBlank()) {
            String literal = search.toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            query.apply("(LOWER(username) LIKE {0} ESCAPE '!' OR LOWER(display_name) LIKE {0} ESCAPE '!')",
                    "%" + literal + "%");
        }
        if (!after.isEmpty()) query.gt("username", after);
        return members.selectList(query.orderByAsc("username").last("LIMIT " + (ACCOUNT_PAGE_SIZE + 1))).stream()
                .map(SharingService::account).toList();
    }

    @Transactional
    public Detail adminDetail(String id) {
        guard.lock();
        Member target = adminTarget(id);
        long now = System.currentTimeMillis();
        audit("VIEW_STATUS", id);
        return new Detail(account(target), now, status(target, now, true), appUsageSnapshot(target), adminPartner(partnerOf(target)));
    }

    @Transactional
    public List<Point> adminRoute(String id, long from, long to) {
        validateRouteRange(from, to);
        guard.lock();
        adminTarget(id);
        audit("VIEW_ROUTE", id);
        return routePoints(new QueryWrapper<PositionSample>().eq("member_id", id), from, to);
    }

    @Transactional
    public List<Activity> adminActivity(String id, Long before) {
        guard.lock();
        adminTarget(id);
        audit("VIEW_ACTIVITY", id);
        return jdbc.query("""
                SELECT id, kind, body, created_at, received_at, viewed_at FROM companion_event
                WHERE sender_id = ? AND created_at >= ? AND id < ? ORDER BY id DESC LIMIT 51
                """, (row, index) -> new Activity(row.getLong("id"), row.getString("kind"), row.getString("body"),
                row.getLong("created_at"), row.getObject("received_at", Long.class), row.getObject("viewed_at", Long.class)),
                id, System.currentTimeMillis() - RETENTION, before == null ? Long.MAX_VALUE : before);
    }

    @Transactional
    public Conversation adminConversation(String id, Long before) {
        guard.lock();
        Member target = adminTarget(id);
        Member partner = partnerOf(target);
        if (partner == null) return new Conversation(null, List.of(), null);
        audit("VIEW_CONVERSATION", id);
        List<ConversationMessage> messages = jdbc.query("""
                SELECT e.id, m.username, m.display_name, e.sender_id, e.body, e.created_at
                FROM companion_event e JOIN member m ON m.id = e.sender_id
                WHERE e.pair_id = ? AND e.sender_id IN (?, ?) AND e.kind = 'MESSAGE'
                    AND e.created_at >= ? AND e.id < ? ORDER BY e.id DESC LIMIT 51
                """, (row, index) -> new ConversationMessage(row.getLong("id"), row.getString("username"),
                row.getString("display_name"), id.equals(row.getString("sender_id")),
                row.getString("body"), row.getLong("created_at")),
                target.pairId, id, partner.id, System.currentTimeMillis() - RETENTION,
                before == null ? Long.MAX_VALUE : before);
        return messages.size() <= 50 ? new Conversation(adminPartner(partner), messages, null)
                : new Conversation(adminPartner(partner), messages.subList(0, 50), messages.get(49).id());
    }

    private static Partner adminPartner(Member member) {
        return member == null ? null : new Partner(member.id, member.username, member.displayName);
    }

    @Transactional
    public List<SavedLocation> adminPlaces(String id) {
        guard.lock();
        adminTarget(id);
        audit("VIEW_PLACES", id);
        return jdbc.query("""
                SELECT name, latitude, longitude, watching FROM companion_place
                WHERE owner_id = ? ORDER BY name, id
                """, (row, index) -> new SavedLocation(row.getString("name"), row.getDouble("latitude"),
                row.getDouble("longitude"), row.getBoolean("watching")), id);
    }

    @Transactional
    public void adminDisable(String id, boolean disabled) {
        guard.lock();
        Member target = adminTarget(id);
        if (target.disabled == disabled) return;
        if (disabled) {
            jdbc.update("""
                    UPDATE member SET disabled = TRUE, session_version = session_version + 1,
                        sharing = FALSE, share_key = NULL, location_unavailable = FALSE
                    WHERE id = ?
                    """, id);
            invitations.delete(new QueryWrapper<Invitation>().eq("member_id", id));
            jdbc.update("DELETE FROM push_device WHERE member_id = ?", id);
            clearSharedStatuses(target);
        } else {
            jdbc.update("UPDATE member SET disabled = FALSE WHERE id = ?", id);
        }
        audit(disabled ? "DISABLE_ACCOUNT" : "ENABLE_ACCOUNT", id);
    }

    public List<AuditEntry> adminAudit(String username, Long from, Long to) {
        if (from != null || to != null) {
            if (from == null || to == null) throw failure(HttpStatus.BAD_REQUEST, "请选择完整日期");
            validateRouteRange(from, to);
        }
        return jdbc.query("""
                SELECT audit.action, audit.target_id, member.username, audit.occurred_at
                FROM admin_audit audit LEFT JOIN member ON member.id = audit.target_id
                WHERE (? = '' OR member.username = ?) AND audit.occurred_at >= ? AND audit.occurred_at < ?
                ORDER BY audit.id DESC LIMIT 100
                """, (row, index) -> new AuditEntry(row.getString("action"), row.getString("target_id"),
                row.getString("username"), row.getLong("occurred_at")),
                username, username, from == null ? 0L : from, to == null ? Long.MAX_VALUE : to);
    }

    private void audit(String action, String id) {
        jdbc.update("INSERT INTO admin_audit(action, target_id, occurred_at) VALUES (?, ?, ?)",
                action, id, System.currentTimeMillis());
    }

    private Member adminTarget(String id) {
        Member target = members.selectById(id);
        if (target == null) throw failure(HttpStatus.NOT_FOUND, "账号不存在");
        return target;
    }

    private static Account account(Member member) {
        return new Account(member.id, member.username, member.displayName, member.disabled, member.pairId != null,
                member.sharing);
    }

    @Transactional
    public List<Point> route(String username, boolean partner, long from, long to) {
        validateRouteRange(from, to);
        guard.lock();
        Member self = current(username);
        Member target = partner ? partnerOf(self) : self;
        if (target == null || target.pairId == null || (partner && !target.sharing)) return List.of();
        return routePoints(query(target), from, to);
    }

    private static void validateRouteRange(long from, long to) {
        if (from < 0 || from >= to || to - from > 25 * 60 * 60 * 1000L) {
            throw failure(HttpStatus.BAD_REQUEST, "一次最多查看一天的轨迹");
        }
    }

    private List<Point> routePoints(QueryWrapper<PositionSample> query, long from, long to) {
        long now = System.currentTimeMillis();
        List<PositionSample> route = samples.selectList(query
                .ge("recorded_at", Math.max(from, now - RETENTION)).lt("recorded_at", to)
                .orderByDesc("recorded_at", "id").last("LIMIT 7500"));
        Collections.reverse(route);
        return route.stream().map(SharingService::point).toList();
    }

    @Transactional
    public void clear(String username) {
        guard.lock();
        Member self = current(username);
        members.update(null, new UpdateWrapper<Member>().eq("id", self.id)
                .set("sharing", false).set("share_key", null).set("location_unavailable", false));
        samples.delete(new QueryWrapper<PositionSample>().eq("member_id", self.id));
        clearInsights(self.id);
        jdbc.update("UPDATE companion_place SET watching = FALSE, armed = FALSE, inside_at = NULL WHERE pair_id = ? AND owner_id <> ?",
                self.pairId, self.id);
        jdbc.update("DELETE FROM companion_event WHERE sender_id = ? AND kind IN ('ARRIVE', 'LEAVE', 'AUTO_ARRIVE', 'AUTO_LEAVE', 'NETWORK', 'CHARGING', 'MOTION', 'LOW_BATTERY')", self.id);
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
        jdbc.update("DELETE FROM app_usage_snapshot WHERE recorded_at < ?", now - DAY);
        jdbc.update("DELETE FROM admin_audit WHERE occurred_at < ?", now - RETENTION);
    }

    @Transactional
    public Feed feed(String username, Long before) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) return new Feed(null, List.of());
        long now = System.currentTimeMillis();
        List<Event> events = jdbc.query("""
                SELECT e.id, e.kind, e.body, m.display_name, e.sender_id, e.created_at,
                       p.id AS fix_id, e.received_at, e.viewed_at
                FROM companion_event e JOIN member m ON e.sender_id = m.id
                LEFT JOIN position_sample p ON p.id = e.sos_sample_id AND p.pair_id = e.pair_id
                    AND p.member_id = m.id AND m.sharing = TRUE AND e.created_at >= ?
                WHERE e.pair_id = ? AND e.id < ? AND (e.kind <> 'NETWORK' OR e.body IN ('WIFI', 'MOBILE')) AND e.created_at >= ?
                    AND (e.kind <> 'PLACE_ARRIVAL' OR e.sender_id <> ?)
                ORDER BY e.id DESC LIMIT 80
                """, (row, i) -> new Event(row.getLong("id"), row.getString("kind"), row.getString("body"),
                row.getString("display_name"), !self.id.equals(row.getString("sender_id")), row.getLong("created_at"),
                row.getString("fix_id") != null, row.getObject("received_at", Long.class), row.getObject("viewed_at", Long.class)),
                now - DAY, self.pairId, before == null ? Long.MAX_VALUE : before, now - RETENTION, self.id);
        return new Feed(self.pairId, events);
    }

    @Transactional
    public void interact(String username, String kind, String body) {
        guard.lock();
        Member self = current(username);
        long now = System.currentTimeMillis();
        checkInteraction(self, kind, now);
        addEvent(self, kind, body.strip(), now);
    }

    @Transactional
    public void sos(String username, boolean includeLocation) {
        createSos(username, includeLocation, id());
    }

    @Transactional
    public SosCreated createSos(String username, boolean includeLocation, String request) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        var existing = jdbc.query("""
                SELECT id FROM companion_event WHERE sender_id = ? AND pair_id = ? AND client_request = ?
                """, (row, index) -> row.getLong("id"), self.id, self.pairId, request);
        if (!existing.isEmpty()) return new SosCreated(existing.getFirst());
        long now = System.currentTimeMillis();
        checkInteraction(self, "SOS", now);
        PositionSample sample = includeLocation && self.sharing ? lastPoint(self, now) : null;
        String sampleId = sample != null && sample.recordedAt >= now - Duration.ofMinutes(5).toMillis()
                && sample.recordedAt <= now && sample.accuracy <= 1000 ? sample.id : null;
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var stmt = connection.prepareStatement("""
                    INSERT INTO companion_event(pair_id, sender_id, kind, body, created_at, sos_sample_id, client_request, location_requested)
                    VALUES (?, ?, 'SOS', ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            stmt.setString(1, self.pairId);
            stmt.setString(2, self.id);
            stmt.setString(3, "请尽快联系我，我需要帮助");
            stmt.setLong(4, now);
            stmt.setString(5, sampleId);
            stmt.setString(6, request);
            stmt.setBoolean(7, includeLocation);
            return stmt;
        }, key);
        return new SosCreated(key.getKey().longValue());
    }

    @Transactional
    public void sosReceipt(String username, long event, boolean viewed) {
        guard.lock();
        Member self = current(username);
        long now = System.currentTimeMillis();
        int changed = jdbc.update("""
                UPDATE companion_event SET received_at = COALESCE(received_at, ?),
                    viewed_at = CASE WHEN ? THEN COALESCE(viewed_at, ?) ELSE viewed_at END
                WHERE id = ? AND pair_id = ? AND sender_id <> ? AND kind = 'SOS' AND created_at >= ?
                """, now, viewed, now, event, self.pairId, self.id, now - DAY);
        if (changed == 0 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM companion_event WHERE id = ? AND pair_id = ?
                    AND sender_id <> ? AND kind = 'SOS' AND created_at >= ?
                """, Integer.class, event, self.pairId, self.id, now - DAY) == 0) {
            throw failure(HttpStatus.NOT_FOUND, "求助已失效");
        }
    }

    @Transactional
    public void sosFix(String username, long event, FreshFix fix) {
        guard.lock();
        Member self = current(username);
        long now = System.currentTimeMillis();
        if (!self.sharing || self.pairId == null) throw failure(HttpStatus.CONFLICT, "位置未共享");
        if (fix.recordedAt() < now - 120_000 || fix.recordedAt() > now + 30_000) {
            throw failure(HttpStatus.BAD_REQUEST, "定位时间无效");
        }
        var previous = jdbc.query("""
                SELECT p.recorded_at FROM companion_event e LEFT JOIN position_sample p ON p.id = e.sos_sample_id
                WHERE e.id = ? AND e.pair_id = ? AND e.sender_id = ? AND e.kind = 'SOS' AND e.location_requested = TRUE AND e.created_at >= ?
                """, (row, index) -> row.getObject("recorded_at", Long.class),
                event, self.pairId, self.id, now - 120_000);
        if (previous.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "求助已失效");
        if (previous.getFirst() != null && previous.getFirst() >= fix.recordedAt()) return;
        var sample = new PositionSample();
        sample.id = id();
        sample.memberId = self.id;
        sample.pairId = self.pairId;
        sample.latitude = fix.point().latitude();
        sample.longitude = fix.point().longitude();
        sample.accuracy = fix.accuracy();
        var last = lastPoint(self, now);
        sample.battery = last == null ? -1 : last.battery;
        sample.charging = last != null && last.charging;
        sample.recordedAt = fix.recordedAt();
        samples.insert(sample);
        jdbc.update("UPDATE companion_event SET sos_sample_id = ? WHERE id = ?", sample.id, event);
    }

    @Transactional
    public Point sosLocation(String username, long id) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) throw failure(HttpStatus.NOT_FOUND, "求助位置已失效");
        return jdbc.query("""
                SELECT p.latitude, p.longitude, p.accuracy, p.battery, p.charging, p.recorded_at
                FROM companion_event e JOIN member m ON m.id = e.sender_id AND m.sharing = TRUE
                JOIN position_sample p ON p.id = e.sos_sample_id AND p.pair_id = e.pair_id AND p.member_id = m.id
                WHERE e.id = ? AND e.pair_id = ? AND e.kind = 'SOS' AND e.created_at >= ?
                """, (row, index) -> new Point(row.getDouble("latitude"), row.getDouble("longitude"),
                row.getDouble("accuracy"), row.getInt("battery"), row.getBoolean("charging"), row.getLong("recorded_at")),
                id, self.pairId, System.currentTimeMillis() - DAY).stream().findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "求助位置已失效"));
    }

    @Transactional
    public List<Place> savedPlaces(String username) {
        Member self = current(username);
        return self.pairId == null ? List.of() : places.findAll(self.pairId, self.id);
    }

    @Transactional
    public Place savePlace(String username, SavedPlace request) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        if (places.findAll(self.pairId, self.id).size() >= 20) throw failure(HttpStatus.CONFLICT, "最多保存 20 个地点");
        return places.add(id(), self.pairId, self.id, request.name().strip(), request.point());
    }

    @Transactional
    public void watchPlace(String username, String id, boolean enabled) {
        guard.lock();
        Member self = current(username);
        Place place = places.findAll(self.pairId, self.id).stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "地点不存在"));
        long now = System.currentTimeMillis();
        Member partner = partnerOf(self);
        if (enabled && (partner == null || !partner.sharing)) {
            throw failure(HttpStatus.CONFLICT, "对方当前未共享位置");
        }
        PositionSample point = enabled ? lastPoint(partner, now) : null;
        places.updateWatch(place, enabled, point, now);
    }

    @Transactional
    public void deletePlace(String username, String id) {
        guard.lock();
        Member self = current(username);
        places.delete(id, self.id, self.pairId);
    }

    private void checkInteraction(Member self, String kind, long now) {
        if (self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        long window = kind.equals("MESSAGE") ? 1000 : 15_000;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companion_event WHERE pair_id = ? AND sender_id = ?
                AND kind = ? AND created_at >= ?
                """, Integer.class, self.pairId, self.id, kind, now - window);
        if (count != null && count > 0) throw failure(HttpStatus.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
    }

    private void notifyLowBattery(Member self, PositionSample sample, PositionSample previous, long now) {
        if (sample.charging || sample.battery < 0 || sample.battery > 15
                || (previous != null && (previous.recordedAt >= sample.recordedAt
                    || (previous.battery >= 0 && previous.battery <= 15 && !previous.charging)))) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companion_event WHERE pair_id = ? AND sender_id = ?
                AND kind = 'LOW_BATTERY' AND created_at >= ?
                """, Integer.class, self.pairId, self.id, now - Duration.ofHours(1).toMillis());
        if (count != null && count == 0) addEvent(self, "LOW_BATTERY", "电量仅剩 " + sample.battery + "%", now);
    }

    private void addEvent(Member self, String kind, String body, long time) {
        jdbc.update("INSERT INTO companion_event(pair_id, sender_id, kind, body, created_at) VALUES (?, ?, ?, ?, ?)",
                self.pairId, self.id, kind, body, time);
    }

    @Transactional
    public void journeySharing(String username, boolean enabled, UUID pairId) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        if (self.journeyEnabled == enabled) return;
        jdbc.update("UPDATE member SET journey_enabled = ?, journey_state = NULL WHERE id = ?", enabled, self.id);
    }

    @Transactional
    public void liveScreenSharing(String username, boolean enabled, UUID pairId) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        if (self.liveScreenEnabled == enabled) return;
        jdbc.update("UPDATE member SET live_screen_enabled = ?, live_screen_state = NULL, live_screen_at = NULL WHERE id = ?",
                enabled, self.id);
    }

    @Transactional
    public void liveScreen(String username, ScreenReport report) {
        guard.lock();
        Member self = current(username);
        if (!self.liveScreenEnabled || !self.sharing || self.pairId == null
                || !report.shareKey().toString().equals(self.shareKey)) {
            throw failure(HttpStatus.CONFLICT, "屏幕状态未授权共享");
        }
        jdbc.update("UPDATE member SET live_screen_state = ?, live_screen_at = ? WHERE id = ?",
                report.state().name(), System.currentTimeMillis(), self.id);
    }

    private JourneyDetector.State journeyState(Member self) {
        if (self.journeyState == null) return null;
        try {
            return json.readValue(self.journeyState, JourneyDetector.State.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return null;
        }
    }

    private void recordJourney(Member self, PositionSample sample, boolean namePlaces) {
        JourneyDetector.State state = journeyState(self);
        var fix = new JourneyDetector.Fix(sample.latitude, sample.longitude, sample.accuracy, sample.recordedAt);
        var update = JourneyDetector.accept(state, fix);
        if (update.state() == state) return;
        try {
            jdbc.update("UPDATE member SET journey_state = ? WHERE id = ?",
                    json.writeValueAsString(update.state()), self.id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Could not store journey state", invalid);
        }
        if (update.event() == null) return;
        boolean arrived = update.event() == JourneyDetector.Event.ARRIVE;
        String kind = arrived ? "AUTO_ARRIVE" : "AUTO_LEAVE";
        var anchor = arrived ? update.state().anchor() : state.anchor();
        String suffix = arrived ? "" : durationAtStop(state);
        addEvent(self, kind, (arrived ? "已到达新的停留区域" : "已离开上一停留区域") + suffix, sample.recordedAt);
        if (namePlaces) {
            placeNames.enrich(self.pairId, self.id, kind, sample.recordedAt, anchor, arrived ? "已到达" : "已离开", suffix);
        }
    }

    private static String durationAtStop(JourneyDetector.State state) {
        long minutes = Math.max(0, (state.away().at() - state.anchor().at()) / 60_000);
        if (minutes == 0) return "";
        if (minutes < 60) return " · 停留约 " + minutes + " 分钟";
        String remainder = minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分钟";
        return " · 停留约 " + minutes / 60 + " 小时" + remainder;
    }

    @Transactional
    public void usageSharing(String username, boolean enabled, UUID pairId) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        jdbc.update("UPDATE member SET usage_enabled = ?, screen_minutes = NULL, screen_date = NULL WHERE id = ?",
                enabled, self.id);
    }

    @Transactional
    public void appUsageSharing(String username, boolean enabled, UUID pairId) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        jdbc.update("UPDATE member SET app_usage_enabled = ? WHERE id = ?", enabled, self.id);
        if (!enabled) jdbc.update("DELETE FROM app_usage_snapshot WHERE member_id = ?", self.id);
    }

    @Transactional
    public void deviceStatusSharing(String username, boolean enabled, UUID pairId) {
        guard.lock();
        Member self = current(username);
        checkPair(self, pairId);
        if (enabled && self.pairId == null) throw failure(HttpStatus.CONFLICT, "请先完成绑定");
        jdbc.update("UPDATE member SET device_status_enabled = ? WHERE id = ?", enabled, self.id);
        if (!enabled) {
            clearDevice(self.id);
            jdbc.update("DELETE FROM companion_event WHERE sender_id = ? AND kind IN ('NETWORK', 'CHARGING', 'MOTION', 'LOW_BATTERY')", self.id);
        }
    }

    @Transactional
    public void saveAppUsage(String username, AppUsageReport report) {
        guard.lock();
        Member self = current(username);
        if (self.pairId == null || !self.sharing || !self.appUsageEnabled
                || !report.shareKey().toString().equals(self.shareKey)) {
            throw failure(HttpStatus.CONFLICT, "应用明细共享已暂停");
        }
        long now = System.currentTimeMillis();
        var utc = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(report.recordedAt()), java.time.ZoneOffset.UTC);
        if (report.recordedAt() < now - 300_000 || report.recordedAt() > now + 120_000
                || report.date().isBefore(utc.minusDays(1)) || report.date().isAfter(utc.plusDays(1))) {
            throw failure(HttpStatus.BAD_REQUEST, "明细日期或采集时间无效");
        }
        List<Long> previous = jdbc.query("SELECT recorded_at FROM app_usage_snapshot WHERE member_id = ?",
                (row, index) -> row.getLong(1), self.id);
        if (!previous.isEmpty() && previous.getFirst() >= report.recordedAt()) return;
        String payload;
        try {
            payload = json.writeValueAsString(report.apps());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Could not encode application totals", error);
        }
        if (previous.isEmpty()) {
            jdbc.update("INSERT INTO app_usage_snapshot(member_id, usage_date, recorded_at, apps_json) VALUES (?, ?, ?, ?)",
                    self.id, report.date().toString(), report.recordedAt(), payload);
        } else {
            jdbc.update("UPDATE app_usage_snapshot SET usage_date = ?, recorded_at = ?, apps_json = ? WHERE member_id = ?",
                    report.date().toString(), report.recordedAt(), payload, self.id);
        }
    }

    @Transactional
    public AppUsageSnapshot partnerAppUsage(String username) {
        guard.lock();
        Member partner = partnerOf(current(username));
        if (partner == null || !partner.sharing || !partner.appUsageEnabled) {
            throw failure(HttpStatus.NOT_FOUND, "对方未共享应用使用明细");
        }
        return appUsageSnapshot(partner);
    }

    private AppUsageSnapshot appUsageSnapshot(Member member) {
        var rows = jdbc.query("""
                SELECT usage_date, recorded_at, apps_json FROM app_usage_snapshot
                WHERE member_id = ? AND recorded_at >= ?
                """, (row, index) -> {
            try {
                List<AppDuration> apps = json.readValue(row.getString("apps_json"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<AppDuration>>() {});
                return new AppUsageSnapshot(row.getString("usage_date"), row.getLong("recorded_at"), apps);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Could not read application totals", error);
            }
        }, member.id, System.currentTimeMillis() - DAY);
        return rows.isEmpty() ? new AppUsageSnapshot(null, 0, List.of()) : rows.getFirst();
    }

    private void recordDeviceStatus(Member self, Reading reading, PositionSample sample, PositionSample previous, long now) {
        String previousNetwork = visibleNetwork(self.networkType);
        String network = visibleNetwork(reading.networkType());
        if (network != null && previousNetwork != null && !network.equals(previousNetwork)) {
            addEvent(self, "NETWORK", network, sample.recordedAt);
        }
        if (network == null) network = previousNetwork;
        if (self.deviceStatusAt != null && previous != null && previous.recordedAt < sample.recordedAt
                && previous.charging != sample.charging) {
            addEvent(self, "CHARGING", sample.charging ? "开始充电" : "已停止充电", sample.recordedAt);
        }
        Long unlock = reading.unlockedAt();
        if (unlock == null || unlock < now - DAY || unlock > now + 120_000
                || (self.deviceUnlockedAt != null && unlock < self.deviceUnlockedAt)) unlock = self.deviceUnlockedAt;
        String model = reading.deviceModel() == null || reading.deviceModel().isBlank() ? self.deviceModel : reading.deviceModel();
        String candidate = motion(previous, sample);
        String settled = self.deviceMotion;
        boolean stale = self.deviceStatusAt != null && sample.recordedAt - self.deviceStatusAt > 180_000;
        if (stale) settled = null;
        if (candidate != null && !stale && candidate.equals(self.candidateMotion) && !candidate.equals(settled)) {
            if (settled != null) addEvent(self, "MOTION", candidate, sample.recordedAt);
            settled = candidate;
        }
        jdbc.update("""
                UPDATE member SET device_model = ?, network_type = ?, device_status_at = ?,
                    device_unlocked_at = ?, device_motion = ?, candidate_motion = ?
                WHERE id = ?
                """, model, network, sample.recordedAt, unlock, settled, candidate, self.id);
    }

    private static String visibleNetwork(String value) {
        return "WIFI".equals(value) || "MOBILE".equals(value) ? value : null;
    }

    private static String motion(PositionSample previous, PositionSample sample) {
        if (previous == null || previous.accuracy > 50 || sample.accuracy > 50) return null;
        long elapsed = sample.recordedAt - previous.recordedAt;
        if (elapsed < 10_000 || elapsed > 90_000) return null;
        double observed = GeoDistance.distanceMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude);
        double distance = Math.max(0, observed - previous.accuracy - sample.accuracy);
        double metersPerSecond = distance / (elapsed / 1000.0);
        if (metersPerSecond > 70) return null;
        if (metersPerSecond >= 6) return "FAST";
        if (metersPerSecond >= .65) return "MOVING";
        return "STILL";
    }

    private void clearDevice(String id) {
        jdbc.update("UPDATE member SET device_model = NULL, network_type = NULL, device_status_at = NULL, device_unlocked_at = NULL, device_motion = NULL, candidate_motion = NULL WHERE id = ?", id);
    }

    private void clearInsights(String id) {
        jdbc.update("DELETE FROM app_usage_snapshot WHERE member_id = ?", id);
        clearDevice(id);
        jdbc.update("UPDATE member SET journey_state = NULL, live_screen_state = NULL, live_screen_at = NULL WHERE id = ?", id);
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
    public String changeDisplayName(String username, String value) {
        String name = value.strip();
        if (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl)) {
            throw failure(HttpStatus.BAD_REQUEST, "请输入单行显示名称");
        }
        guard.lock();
        Member member = current(username);
        jdbc.update("UPDATE member SET display_name = ? WHERE id = ?", name, member.id);
        return name;
    }

    @Transactional
    public void changePassword(String username, SharingController.PasswordChange change) {
        guard.lock();
        Member member = current(username);
        String current = change.currentPassword();
        String next = change.newPassword();
        if (current.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(current, member.passwordHash)) {
            throw failure(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (next.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw failure(HttpStatus.BAD_REQUEST, "新密码不能超过 72 字节");
        }
        if (next.equals(current)) throw failure(HttpStatus.BAD_REQUEST, "新密码须与当前密码不同");
        jdbc.update("UPDATE member SET password_hash = ?, session_version = session_version + 1 WHERE id = ?",
                passwords.encode(next), member.id);
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
        clearInsights(member.id);
        members.deleteById(member.id);
    }

    private Status status(Member member, long now) {
        return status(member, now, false);
    }

    private Status status(Member member, long now, boolean administrator) {
        boolean sharing = member.sharing && member.pairId != null;
        boolean showStored = administrator || sharing;
        PositionSample latest = administrator ? latestPoint(new QueryWrapper<PositionSample>().eq("member_id", member.id), now)
                : sharing ? lastPoint(member, now) : null;
        DeviceStatus device = showStored && (administrator || member.deviceStatusEnabled) && member.deviceStatusAt != null
                ? new DeviceStatus(member.deviceModel, visibleNetwork(member.networkType), member.deviceStatusAt,
                        member.deviceUnlockedAt, member.deviceMotion) : null;
        var journeyState = showStored && (administrator || member.journeyEnabled) ? journeyState(member) : null;
        JourneyStatus journey = journeyState != null && now - journeyState.lastAt() <= 240_000
                && (journeyState.stopped() || journeyState.inTrip())
                ? new JourneyStatus(journeyState.stopped() ? "STOPPED" : "TRAVELLING",
                        journeyState.anchor().at(), journeyState.lastAt()) : null;
        ScreenStatus screen = showStored && (administrator || member.liveScreenEnabled) && member.liveScreenAt != null
                ? new ScreenStatus(ScreenState.valueOf(member.liveScreenState), member.liveScreenAt) : null;
        return new Status(member.displayName, sharing, latest == null ? null : point(latest),
                showStored && (administrator || member.usageEnabled) ? member.screenMinutes : null,
                showStored && (administrator || member.usageEnabled) ? member.screenDate : null, member.usageEnabled,
                member.appUsageEnabled, member.deviceStatusEnabled, device,
                member.journeyEnabled, journey, member.liveScreenEnabled, screen, member.locationUnavailable);
    }

    private PositionSample lastPoint(Member member, long now) {
        return latestPoint(query(member), now);
    }

    private PositionSample latestPoint(QueryWrapper<PositionSample> query, long now) {
        List<PositionSample> latest = samples.selectList(query.ge("recorded_at", now - RETENTION)
                .orderByDesc("recorded_at", "id").last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.getFirst();
    }

    private QueryWrapper<PositionSample> query(Member member) {
        return new QueryWrapper<PositionSample>().eq("pair_id", member.pairId).eq("member_id", member.id);
    }

    private void setPair(Member member, String pairId) {
        members.update(null, new UpdateWrapper<Member>().eq("id", member.id).set("pair_id", pairId)
                .set("sharing", false).set("share_key", null).set("location_unavailable", false)
                .set("usage_enabled", false).set("screen_minutes", null).set("screen_date", null)
                .set("app_usage_enabled", false).set("device_status_enabled", false)
                .set("journey_enabled", false).set("live_screen_enabled", false));
        clearInsights(member.id);
    }

    private Member findMember(String username) {
        return members.selectOne(new QueryWrapper<Member>().eq("username", username));
    }

    private Member current(String username) {
        Member member = findMember(username);
        if (member == null || member.disabled) throw failure(HttpStatus.UNAUTHORIZED, "请重新登录");
        return member;
    }

    private Member partnerOf(Member self) {
        if (self.pairId == null) return null;
        return members.selectOne(new QueryWrapper<Member>().eq("pair_id", self.pairId).ne("id", self.id));
    }

    private static Member requireUnpaired(Member member) {
        if (member == null || member.disabled || member.pairId != null) throw failure(HttpStatus.CONFLICT, "已有绑定关系");
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
