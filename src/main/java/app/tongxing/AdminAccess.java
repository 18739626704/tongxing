package app.tongxing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AdminAccess {
    private final String username;
    private final String passwordHash;

    AdminAccess(@Value("${ADMIN_USERNAME:}") String username,
                @Value("${ADMIN_PASSWORD_HASH:}") String passwordHash) {
        this.username = username.strip();
        this.passwordHash = passwordHash.strip();
        if (!this.passwordHash.isEmpty() && !this.passwordHash.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("ADMIN_PASSWORD_HASH must be a BCrypt hash");
        }
        if (!this.passwordHash.isEmpty() && this.username.isEmpty()) {
            throw new IllegalArgumentException("ADMIN_USERNAME is required with ADMIN_PASSWORD_HASH");
        }
    }

    boolean enabled() { return !passwordHash.isEmpty(); }
    String username() { return username; }
    String encodedPassword() { return "{bcrypt}" + passwordHash; }
}
