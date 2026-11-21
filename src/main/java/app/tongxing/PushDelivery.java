package app.tongxing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class PushDelivery {
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GoogleCredentials credentials;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    PushDelivery(JdbcTemplate jdbc, ObjectMapper json,
                 @Value("${FCM_SERVICE_ACCOUNT_JSON:}") String config) throws Exception {
        this.jdbc = jdbc;
        this.json = json;
        if (config.isBlank()) {
            credentials = null;
            endpoint = null;
            return;
        }
        ServiceAccountCredentials account = ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        credentials = account.createScoped(List.of(SCOPE));
        String project = account.getProjectId();
        if (project == null || !project.matches("[a-z][a-z0-9-]{4,29}")) {
            throw new IllegalArgumentException("Invalid Firebase project ID");
        }
        endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + project + "/messages:send");
    }

    @Scheduled(fixedDelay = 5000)
    void deliver() {
        if (credentials == null) return;
        try {
            var pending = jdbc.query("""
                    SELECT d.token, d.member_id, MAX(e.id) AS last_id,
                           MAX(CASE WHEN e.kind = 'SOS' THEN 1 ELSE 0 END) AS urgent
                    FROM push_device d JOIN member m ON m.id = d.member_id
                    JOIN companion_event e ON e.pair_id = m.pair_id AND e.sender_id <> m.id
                    WHERE e.id > d.delivered_through AND e.created_at >= ?
                    GROUP BY d.token, d.member_id ORDER BY urgent DESC, last_id LIMIT 10
                    """, (rs, row) -> new Wakeup(rs.getString("token"), rs.getString("member_id"),
                    rs.getLong("last_id"), rs.getBoolean("urgent")),
                    System.currentTimeMillis() - Duration.ofHours(1).toMillis());
            if (pending.isEmpty()) return;
            credentials.refreshIfExpired();
            for (Wakeup wakeup : pending) deliver(wakeup);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            LoggerFactory.getLogger(PushDelivery.class).warn("FCM delivery deferred ({})", error.getClass().getSimpleName());
        }
    }

    private void deliver(Wakeup wakeup) throws Exception {
        // The data message only wakes the app. Feed contents still require its HTTPS login.
        String body = json.writeValueAsString(Map.of("message", Map.of(
                "token", wakeup.token(), "data", Map.of("kind", "feed"),
                "android", Map.of("priority", wakeup.urgent() ? "HIGH" : "NORMAL", "ttl", "3600s",
                        "collapse_key", wakeup.urgent() ? "companion_sos" : "companion_feed"))));
        var response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            jdbc.update("""
                    UPDATE push_device SET delivered_through = ?
                    WHERE token = ? AND member_id = ? AND delivered_through < ?
                    """, wakeup.lastId(), wakeup.token(), wakeup.memberId(), wakeup.lastId());
        } else if (response.statusCode() == 404 && response.body().contains("UNREGISTERED")) {
            jdbc.update("DELETE FROM push_device WHERE token = ? AND member_id = ?", wakeup.token(), wakeup.memberId());
        } else {
            LoggerFactory.getLogger(PushDelivery.class).warn("FCM returned HTTP {}", response.statusCode());
        }
    }

    private record Wakeup(String token, String memberId, long lastId, boolean urgent) {}
}
