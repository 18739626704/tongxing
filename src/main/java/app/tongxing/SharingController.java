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
    record Consent(@NotNull Boolean enabled) {}
    record Reading(@NotNull UUID id, @NotNull UUID shareKey,
                   @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                   @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                   @NotNull @DecimalMin("0") @DecimalMax("10000") Double accuracy,
                   @NotNull @Min(-1) @Max(100) Integer battery, @NotNull Boolean charging,
                   @NotNull Long recordedAt, @Min(0) @Max(1500) Integer screenMinutes,
                   @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String screenDate) {}
    record Point(double latitude, double longitude, double accuracy, int battery, boolean charging, long recordedAt) {}
    record Status(String displayName, boolean sharing, Point lastLocation, Integer screenMinutes, String screenDate, boolean usageEnabled) {}
    record Overview(Status self, Status partner, long serverTime) {}
    record ShareState(String shareKey) {}
    record InviteCode(String code, long expiresAt) {}

    record Message(@NotBlank @Size(max = 500) String text) {}
    record PlaceInput(@NotBlank @Size(max = 40) String name,
                      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                      @NotNull @Min(100) @Max(3000) Integer radius) {}
    record Place(String id, String name, double latitude, double longitude, int radius,
                 Boolean inside, Long observedAt) {}
    record Event(long id, String kind, String text, String sender, boolean incoming, long createdAt) {}
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
        return sharing.consent(user.getName(), consent.enabled());
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

    @PostMapping("/sos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sos(Principal user) { sharing.interact(user.getName(), "SOS", "请尽快联系我，我需要帮助"); }

    @GetMapping("/places")
    List<Place> places(Principal user) { return sharing.places(user.getName()); }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    void place(Principal user, @Valid @RequestBody PlaceInput place) { sharing.addPlace(user.getName(), place); }

    @DeleteMapping("/places/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePlace(Principal user, @PathVariable UUID id) { sharing.removePlace(user.getName(), id); }

    @PutMapping("/usage-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void usageSharing(Principal user, @Valid @RequestBody Consent consent) {
        sharing.usageSharing(user.getName(), consent.enabled());
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
