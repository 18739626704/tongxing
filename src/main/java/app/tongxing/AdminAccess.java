package app.tongxing;

import app.tongxing.store.WriteGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
class AdminAccess {
    static final String CHANGE_PASSWORD = "/admin/api/password";
    private static final String INITIAL_PASSWORD = "123456";
    private final String username;
    private final String configuredHash;
    private final String bootstrapCode;
    private final JdbcTemplate jdbc;
    private final WriteGuard guard;
    private final PasswordEncoder passwords;
    private final String initialHash;

    AdminAccess(@Value("${ADMIN_USERNAME:}") String username,
                @Value("${ADMIN_PASSWORD_HASH:}") String passwordHash,
                @Value("${ADMIN_BOOTSTRAP_CODE:}") String bootstrapCode,
                JdbcTemplate jdbc, WriteGuard guard, PasswordEncoder passwords) {
        this.bootstrapCode = bootstrapCode.strip();
        this.configuredHash = passwordHash.strip();
        this.username = !this.bootstrapCode.isEmpty() && configuredHash.isEmpty() ? "admin" : username.strip();
        this.jdbc = jdbc;
        this.guard = guard;
        this.passwords = passwords;
        if (!configuredHash.isEmpty() && !configuredHash.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("ADMIN_PASSWORD_HASH must be a BCrypt hash");
        }
        if (!configuredHash.isEmpty() && this.username.isEmpty()) {
            throw new IllegalArgumentException("ADMIN_USERNAME is required with ADMIN_PASSWORD_HASH");
        }
        if (!this.bootstrapCode.isEmpty() && this.bootstrapCode.length() < 20) {
            throw new IllegalArgumentException("ADMIN_BOOTSTRAP_CODE must have at least 20 characters");
        }
        initialHash = !this.bootstrapCode.isEmpty() && configuredHash.isEmpty() ? passwords.encode(INITIAL_PASSWORD) : "";
    }

    record Credential(String hash, long revision, boolean initial) {}

    static final class Principal extends User {
        final long revision;
        final boolean mustChangePassword;

        Principal(String username, Credential credential) {
            super(username, credential.hash(), List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            revision = credential.revision();
            mustChangePassword = credential.initial();
        }
    }

    boolean enabled() { return !configuredHash.isEmpty() || !initialHash.isEmpty(); }

    boolean initialLogin() { return enabled() && credential().initial(); }

    boolean acceptsBootstrapCode(String input) {
        if (!initialLogin()) return true;
        if (input == null || input.length() > 256) return false;
        return MessageDigest.isEqual(bootstrapCode.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8));
    }

    Principal load(String login) {
        if (!enabled() || !username.equalsIgnoreCase(login)) throw new UsernameNotFoundException("Admin account not found");
        return new Principal(username, credential());
    }

    boolean valid(Principal principal) {
        return enabled() && username.equals(principal.getUsername()) && principal.revision == credential().revision();
    }

    private Credential credential() {
        List<Credential> saved = jdbc.query("SELECT password_hash, revision FROM admin_password WHERE id = 1",
                (row, index) -> new Credential(row.getString("password_hash"), row.getLong("revision"), false));
        if (!saved.isEmpty()) return saved.getFirst();
        return configuredHash.isEmpty() ? new Credential(initialHash, 0, true)
                : new Credential("{bcrypt}" + configuredHash, 0, false);
    }

    @Transactional
    public void changePassword(Principal principal, String current, String next) {
        if (next.isBlank() || next.length() < 10 || next.length() > 64 || next.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码须为 10–64 个字符，且不超过 72 字节");
        }
        if (INITIAL_PASSWORD.equals(next) || next.equals(current)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码请与当前密码和初始密码不同");
        }
        guard.lock();
        Credential old = credential();
        if (principal.revision != old.revision()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录");
        }
        if (current.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(current, old.hash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        String hash = passwords.encode(next);
        if (old.revision() == 0) jdbc.update("INSERT INTO admin_password(id, password_hash, revision) VALUES (1, ?, 1)", hash);
        else jdbc.update("UPDATE admin_password SET password_hash = ?, revision = revision + 1 WHERE id = 1", hash);
    }
}
