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
                   boolean paired, boolean sharing) {}
    record Accounts(List<Account> accounts, String next) {}
    static final int ACCOUNT_PAGE_SIZE = 20;
    record Partner(String id, String username, String displayName) {}
    record Detail(Account account, long serverTime, SharingController.Status status,
                  SharingController.AppUsageSnapshot apps, Partner partner) {}
    record ConversationMessage(long id, String username, String displayName, boolean outgoing, String text, long createdAt) {}
    record Conversation(Partner partner, List<ConversationMessage> messages, Long before) {}
    record Activity(long id, String kind, String text, long createdAt, Long receivedAt, Long viewedAt) {}
    record Activities(List<Activity> items, Long before) {}
    record SavedLocation(String name, double latitude, double longitude, boolean watching) {}
    record AccountState(@NotNull Boolean disabled) {}
    record PasswordChange(@NotNull @Size(max = 128) String currentPassword,
                          @NotNull @Size(max = 64) String newPassword) {}
    record AuditEntry(String action, String targetId, String username, long occurredAt) {}

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
        return Map.of("header", token.getHeaderName(), "token", token.getToken(), "enabled", admin.enabled());
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
        return found.size() <= ACCOUNT_PAGE_SIZE ? new Accounts(found, null)
                : new Accounts(found.subList(0, ACCOUNT_PAGE_SIZE), found.get(ACCOUNT_PAGE_SIZE - 1).username());
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

    @GetMapping("/api/accounts/{id}/activity")
    @ResponseBody
    Activities activity(@PathVariable UUID id, @RequestParam(required = false) Long before) {
        List<Activity> items = sharing.adminActivity(id.toString(), before);
        return items.size() <= 50 ? new Activities(items, null)
                : new Activities(items.subList(0, 50), items.get(49).id());
    }

    @GetMapping("/api/accounts/{id}/conversation")
    @ResponseBody
    Conversation conversation(@PathVariable UUID id, @RequestParam(required = false) Long before) {
        return sharing.adminConversation(id.toString(), before);
    }

    @GetMapping("/api/accounts/{id}/places")
    @ResponseBody
    List<SavedLocation> places(@PathVariable UUID id) { return sharing.adminPlaces(id.toString()); }

    @GetMapping("/api/audit")
    @ResponseBody
    List<AuditEntry> audit(@RequestParam(defaultValue = "") @Size(max = 32) String username,
                           @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        return sharing.adminAudit(username.strip(), from, to);
    }
}
