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
                   @NotNull Long recordedAt) {}
    record Point(double latitude, double longitude, double accuracy, int battery, boolean charging, long recordedAt) {}
    record Status(String displayName, boolean sharing, Point lastLocation) {}
    record Overview(Status self, Status partner, long serverTime) {}
    record ShareState(String shareKey) {}
    record InviteCode(String code, long expiresAt) {}

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

    @DeleteMapping("/positions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(Principal user) { sharing.clear(user.getName()); }
}
