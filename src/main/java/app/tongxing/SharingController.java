package app.tongxing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class SharingController {
    record Registration(@NotBlank(message = "请输入用户名")
                        @Pattern(regexp = "[a-z0-9_]{3,32}", message = "用户名须为 3–32 位小写英文字母、数字或下划线") String username,
                        @NotBlank(message = "请输入密码")
                        @Size(min = 10, max = 64, message = "密码须为 10–64 个字符") String password,
                        @Size(max = 32, message = "显示名称最多 32 个字符") String displayName) {}
    record Accept(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16}") String code) {}
    record Consent(@NotNull Boolean enabled, UUID pairId, Boolean locationUnavailable) {}
    record Reading(@NotNull UUID id, @NotNull UUID shareKey,
                   @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                   @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                   @NotNull @DecimalMin("0") @DecimalMax("10000") Double accuracy,
                   @NotNull @Min(-1) @Max(100) Integer battery, @NotNull Boolean charging,
                   @NotNull Long recordedAt, @Min(0) @Max(1500) Integer screenMinutes,
                   @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String screenDate,
                   @Size(max = 80) String deviceModel,
                   @Pattern(regexp = "WIFI|MOBILE|ETHERNET|VPN|OTHER|OFFLINE") String networkType, @Positive Long unlockedAt, Boolean amapPlaceNames) {}
    record Point(double latitude, double longitude, double accuracy, int battery, boolean charging, long recordedAt) {}
    record Status(String displayName, boolean sharing, Point lastLocation, Integer screenMinutes, String screenDate,
                  boolean usageEnabled, boolean appUsageEnabled, boolean deviceStatusEnabled, DeviceStatus device,
                  boolean journeyEnabled, JourneyStatus journey, boolean liveScreenEnabled, ScreenStatus liveScreen, boolean locationUnavailable) {}
    record JourneyStatus(String state, long since, long updatedAt) {}
    enum ScreenState { OFF, LOCKED, UNLOCKED }
    record ScreenStatus(ScreenState state, long updatedAt) {}
    record ScreenReport(@NotNull UUID shareKey, @NotNull ScreenState state) {}
    record DeviceStatus(String model, String network, long updatedAt, Long unlockedAt, String motion) {}
    record AppDuration(@NotBlank @Size(max = 80) String name, @NotNull @Min(1) @Max(90_000) Integer seconds) {}
    record AppUsageReport(@NotNull UUID shareKey, @NotNull java.time.LocalDate date, @NotNull Long recordedAt,
                          @NotNull @Size(max = 200) List<@NotNull @Valid AppDuration> apps) {}
    record AppUsageSnapshot(String date, long recordedAt, List<AppDuration> apps) {}
    record Overview(Status self, Status partner, long serverTime, String pairId, MapConfig mapConfig) {}
    record MapConfig(String amapAndroidKey, boolean placeNamesAvailable) {}
    record ShareState(String shareKey) {}
    record InviteCode(String code, long expiresAt) {}

    record Message(@NotBlank @Size(max = 500) String text) {}
    record Routine(@NotNull Boolean resting) {}
    record Sos(@NotNull Boolean includeLocation) {}
    record SosRequest(@NotNull UUID requestId, @NotNull Boolean includeLocation) {}
    record SosCreated(long id) {}
    record SosReceipt(@NotNull Boolean viewed) {}
    record Coordinates(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                       @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
    record FreshFix(@NotNull @Valid Coordinates point,
                    @NotNull @DecimalMin("0") @DecimalMax("1000") Double accuracy, @NotNull Long recordedAt) {}
    record SavedPlace(@NotBlank @Size(max = 40) String name, @NotNull @Valid Coordinates point) {}
    record ArrivalWatch(@NotNull Boolean enabled) {}
    record Place(String id, String name, double latitude, double longitude, boolean watching, boolean armed) {}
    record CheckIn(@NotNull CheckInKind kind) {}
    enum CheckInKind { GOOD_MORNING, GOOD_NIGHT, ARRIVED, HEADING_OUT }
    record Event(long id, String kind, String text, String sender, boolean incoming, long createdAt, boolean hasLocation, Long receivedAt, Long viewedAt) {}
    record Feed(String pairId, List<Event> events) {}
    record PushToken(@NotBlank @Size(max = 512) @Pattern(regexp = "[a-zA-Z0-9:_-]+") String token) {}
    record DeleteAccount(@NotBlank @Size(max = 64) String password) {}
    record PasswordChange(@NotBlank @Size(max = 64) String currentPassword,
                          @NotBlank @Size(min = 10, max = 64) String newPassword) {}

    private final SharingService sharing;

    SharingController(SharingService sharing) {
        this.sharing = sharing;
    }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Registration registration) { sharing.register(registration); }

    @GetMapping("/overview")
    Overview overview(Principal user) { return sharing.overview(user.getName()); }

    @PostMapping("/invite")
    InviteCode invite(Principal user) { return sharing.invite(user.getName()); }

    @PostMapping("/pair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pair(Principal user, @Valid @RequestBody Accept accept) { sharing.pair(user.getName(), accept.code()); }

    @DeleteMapping("/pair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpair(Principal user) { sharing.unpair(user.getName()); }

    @PutMapping("/sharing")
    ShareState share(Principal user, @Valid @RequestBody Consent consent) {
        return sharing.consent(user.getName(), consent.enabled(), consent.pairId(), Boolean.TRUE.equals(consent.locationUnavailable()));
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(Principal user, @Valid @RequestBody Reading reading) { sharing.record(user.getName(), reading); }

    @GetMapping("/positions")
    List<Point> route(Principal user, @RequestParam boolean partner, @RequestParam long from, @RequestParam long to) {
        return sharing.route(user.getName(), partner, from, to);
    }

    @GetMapping("/feed")
    Feed feed(Principal user, @RequestParam(required = false) @Positive Long before) {
        return sharing.feed(user.getName(), before);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void message(Principal user, @Valid @RequestBody Message message) {
        sharing.interact(user.getName(), "MESSAGE", message.text());
    }

    @PostMapping("/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remind(Principal user) { sharing.interact(user.getName(), "REMINDER", "记得休息一下"); }

    @PostMapping("/routine")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void routine(Principal user, @Valid @RequestBody Routine routine) {
        sharing.interact(user.getName(), "ROUTINE", routine.resting() ? "开始休息" : "已起床");
    }

    @PostMapping("/sos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sos(Principal user, @Valid @RequestBody(required = false) Sos options) {
        sharing.sos(user.getName(), options != null && options.includeLocation());
    }

    @PostMapping("/sos/requests")
    SosCreated createSos(Principal user, @Valid @RequestBody SosRequest request) {
        return sharing.createSos(user.getName(), request.includeLocation(), request.requestId().toString());
    }

    @PutMapping("/sos/{id}/receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sosReceipt(Principal user, @PathVariable @Positive long id, @Valid @RequestBody SosReceipt receipt) {
        sharing.sosReceipt(user.getName(), id, receipt.viewed());
    }

    @PutMapping("/sos/{id}/fix")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sosFix(Principal user, @PathVariable @Positive long id, @Valid @RequestBody FreshFix fix) {
        sharing.sosFix(user.getName(), id, fix);
    }

    @GetMapping("/saved-places")
    List<Place> savedPlaces(Principal user) { return sharing.savedPlaces(user.getName()); }

    @PostMapping("/saved-places")
    Place savePlace(Principal user, @Valid @RequestBody SavedPlace place) {
        return sharing.savePlace(user.getName(), place);
    }

    @PutMapping("/saved-places/{id}/watch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void watchPlace(Principal user, @PathVariable UUID id, @Valid @RequestBody ArrivalWatch watch) {
        sharing.watchPlace(user.getName(), id.toString(), watch.enabled());
    }

    @DeleteMapping("/saved-places/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlace(Principal user, @PathVariable UUID id) { sharing.deletePlace(user.getName(), id.toString()); }

    @GetMapping("/sos/{id}")
    Point sosLocation(Principal user, @PathVariable @Positive long id) {
        return sharing.sosLocation(user.getName(), id);
    }

    @PostMapping("/check-ins")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void checkIn(Principal user, @Valid @RequestBody CheckIn checkIn) {
        String message = switch (checkIn.kind()) {
            case GOOD_MORNING -> "早上好";
            case GOOD_NIGHT -> "晚安";
            case ARRIVED -> "我到了";
            case HEADING_OUT -> "我出发了";
        };
        sharing.interact(user.getName(), "CHECK_IN", message);
    }

    @GetMapping("/places")
    List<Object> retiredPlaces() { return List.of(); }

    @RequestMapping(path = {"/places", "/places/{id}"}, method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    void retiredPlaceUpdates() {
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.GONE, "手动地点围栏已停用");
    }

    @PutMapping("/journey-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void journeySharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.journeySharing(user.getName(), consent.enabled(), consent.pairId());
    }

    @PutMapping("/live-screen-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void liveScreenSharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.liveScreenSharing(user.getName(), consent.enabled(), consent.pairId());
    }

    @PutMapping("/live-screen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void liveScreen(Principal user, @Valid @RequestBody ScreenReport report) {
        sharing.liveScreen(user.getName(), report);
    }

    @GetMapping("/sharing")
    ShareState resumeSharing(Principal user) { return sharing.resumeSharing(user.getName()); }

    @PutMapping("/app-usage-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void appUsageSharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.appUsageSharing(user.getName(), consent.enabled(), consent.pairId());
    }

    @PutMapping("/app-usage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void appUsage(Principal user, @Valid @RequestBody AppUsageReport report) {
        sharing.saveAppUsage(user.getName(), report);
    }

    @GetMapping("/app-usage")
    AppUsageSnapshot appUsage(Principal user) { return sharing.partnerAppUsage(user.getName()); }

    @PutMapping("/device-status-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deviceStatusSharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.deviceStatusSharing(user.getName(), consent.enabled(), consent.pairId());
    }

    @PutMapping("/usage-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void usageSharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.usageSharing(user.getName(), consent.enabled(), consent.pairId());
    }

    @PutMapping("/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void registerPush(Principal user, @Valid @RequestBody PushToken request) {
        sharing.pushToken(user.getName(), request.token(), true);
    }

    @DeleteMapping("/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePush(Principal user, @Valid @RequestBody PushToken request) {
        sharing.pushToken(user.getName(), request.token(), false);
    }

    record Profile(@NotBlank @Size(max = 32) String displayName) {}

    @PutMapping("/account/profile")
    Profile changeDisplayName(Principal user, @Valid @RequestBody Profile profile) {
        return new Profile(sharing.changeDisplayName(user.getName(), profile.displayName()));
    }

    @PutMapping("/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(Principal user, @Valid @RequestBody PasswordChange request,
                        jakarta.servlet.http.HttpServletRequest http) {
        sharing.changePassword(user.getName(), request);
        var session = http.getSession(false);
        if (session != null) session.invalidate();
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(Principal user, @Valid @RequestBody DeleteAccount request,
                       jakarta.servlet.http.HttpServletRequest http) {
        sharing.deleteAccount(user.getName(), request.password());
        var session = http.getSession(false);
        if (session != null) session.invalidate();
    }

    @DeleteMapping("/positions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(Principal user) { sharing.clear(user.getName()); }
}
