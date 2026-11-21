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
    record Registration(@NotBlank @Pattern(regexp = "[a-z0-9_]{3,32}") String username,
                        @NotBlank @Size(min = 10, max = 64) String password,
                        @NotBlank @Size(max = 32) String displayName,
                        @NotBlank @Size(max = 256) String enrollmentKey) {}
    record Accept(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16}") String code) {}
    record Consent(@NotNull Boolean enabled, UUID pairId) {}
    record Reading(@NotNull UUID id, @NotNull UUID shareKey,
                   @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                   @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                   @NotNull @DecimalMin("0") @DecimalMax("10000") Double accuracy,
                   @NotNull @Min(-1) @Max(100) Integer battery, @NotNull Boolean charging,
                   @NotNull Long recordedAt, @Min(0) @Max(1500) Integer screenMinutes,
                   @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String screenDate,
                   @Size(max = 80) String deviceModel,
                   @Pattern(regexp = "WIFI|MOBILE|ETHERNET|VPN|OTHER|OFFLINE") String networkType, @Positive Long unlockedAt) {}
    record Point(double latitude, double longitude, double accuracy, int battery, boolean charging, long recordedAt) {}
    record Status(String displayName, boolean sharing, Point lastLocation, Integer screenMinutes, String screenDate,
                  boolean usageEnabled, boolean appUsageEnabled, boolean deviceStatusEnabled, DeviceStatus device) {}
    record DeviceStatus(String model, String network, long updatedAt, Long unlockedAt, String motion) {}
    record AppDuration(@NotBlank @Size(max = 80) String name, @NotNull @Min(1) @Max(90_000) Integer seconds) {}
    record AppUsageReport(@NotNull UUID shareKey, @NotNull java.time.LocalDate date, @NotNull Long recordedAt,
                          @NotNull @Size(max = 200) List<@NotNull @Valid AppDuration> apps) {}
    record AppUsageSnapshot(String date, long recordedAt, List<AppDuration> apps) {}
    record Overview(Status self, Status partner, long serverTime, String pairId) {}
    record ShareState(String shareKey) {}
    record InviteCode(String code, long expiresAt) {}

    record Message(@NotBlank @Size(max = 500) String text) {}
    record Routine(@NotNull Boolean resting) {}
    record Sos(@NotNull Boolean includeLocation) {}
    record CheckIn(@NotNull CheckInKind kind) {}
    enum CheckInKind { GOOD_MORNING, GOOD_NIGHT, ARRIVED, HEADING_OUT }
    record PlaceInput(@NotBlank @Size(max = 40) String name,
                      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                      @NotNull @Min(100) @Max(3000) Integer radius) {}
    record PlaceSettings(@NotBlank @Size(max = 40) String name, @NotNull @Min(100) @Max(3000) Integer radius) {}
    record Place(String id, String name, double latitude, double longitude, int radius,
                 Boolean inside, Long observedAt) {}
    record Event(long id, String kind, String text, String sender, boolean incoming, long createdAt, boolean hasLocation) {}
    record Feed(String pairId, List<Event> events) {}
    record PushToken(@NotBlank @Size(max = 512) @Pattern(regexp = "[a-zA-Z0-9:_-]+") String token) {}
    record DeleteAccount(@NotBlank @Size(max = 64) String password) {}

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
        return sharing.consent(user.getName(), consent.enabled(), consent.pairId());
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
    List<Place> places(Principal user) { return sharing.places(user.getName()); }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    void place(Principal user, @Valid @RequestBody PlaceInput place) { sharing.addPlace(user.getName(), place); }

    @PutMapping("/places/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void editPlace(Principal user, @PathVariable UUID id, @Valid @RequestBody PlaceSettings options) {
        sharing.editPlace(user.getName(), id, options);
    }

    @DeleteMapping("/places/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePlace(Principal user, @PathVariable UUID id) { sharing.removePlace(user.getName(), id); }

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
