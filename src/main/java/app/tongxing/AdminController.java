package app.tongxing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
class AdminController {
    record Account(String id, String username, String displayName, boolean disabled,
                   boolean paired, boolean sharing, boolean adminLocationAllowed) {}
    record Accounts(List<Account> accounts, String next) {}
    record Detail(Account account, long serverTime, SharingController.Status status, SharingController.AppUsageSnapshot apps) {}
    record AccountState(@NotNull Boolean disabled) {}
    record PasswordChange(@NotNull @Size(max = 128) String currentPassword,
                          @NotNull @Size(max = 64) String newPassword) {}
    record AuditEntry(String action, String targetId, long occurredAt) {}

    private final SharingService sharing;
    private final AdminAccess admin;

    AdminController(SharingService sharing, AdminAccess admin) {
        this.sharing = sharing;
        this.admin = admin;
    }

    @GetMapping({"", "/"})
    String index() { return "forward:/admin/index.html"; }

    @GetMapping("/csrf")
    @ResponseBody
    Map<String, Object> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken(), "enabled", admin.enabled(),
                "initialLogin", admin.initialLogin());
    }

    @GetMapping("/api/session")
    @ResponseBody
    Map<String, Object> session(@AuthenticationPrincipal AdminAccess.Principal principal) {
        return Map.of("username", principal.getUsername(), "mustChangePassword", principal.mustChangePassword);
    }

    @PutMapping("/api/password")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal AdminAccess.Principal principal,
                        @Valid @RequestBody PasswordChange change, HttpServletRequest request) {
        admin.changePassword(principal, change.currentPassword(), change.newPassword());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    @GetMapping("/api/accounts")
    @ResponseBody
    Accounts accounts(@RequestParam(defaultValue = "") @Size(max = 32) String search,
                      @RequestParam(defaultValue = "") @Size(max = 32) String after) {
        List<Account> found = sharing.adminAccounts(search.strip(), after);
        return found.size() <= 50 ? new Accounts(found, null)
                : new Accounts(found.subList(0, 50), found.get(49).username());
    }

    @GetMapping("/api/accounts/{id}")
    @ResponseBody
    Detail detail(@PathVariable UUID id) { return sharing.adminDetail(id.toString()); }

    @PutMapping("/api/accounts/{id}")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accountState(@PathVariable UUID id, @Valid @RequestBody AccountState state) {
        sharing.adminDisable(id.toString(), state.disabled());
    }

    @GetMapping("/api/accounts/{id}/positions")
    @ResponseBody
    List<SharingController.Point> positions(@PathVariable UUID id, @RequestParam long from, @RequestParam long to) {
        return sharing.adminRoute(id.toString(), from, to);
    }

    @GetMapping("/api/audit")
    @ResponseBody
    List<AuditEntry> audit() { return sharing.adminAudit(); }
}
