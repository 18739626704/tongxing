package app.tongxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
final class AmapPlaceNames {
    private static final String BASE = "https://restapi.amap.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardPolicy());
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String key;

    AmapPlaceNames(JdbcTemplate jdbc, ObjectMapper json, @Value("${AMAP_WEB_SERVICE_KEY:}") String key) {
        this.jdbc = jdbc;
        this.json = json;
        this.key = key.strip();
    }

    boolean configured() { return key.matches("[0-9a-fA-F]{32}"); }

    void enrich(String pair, String sender, String kind, long at, JourneyDetector.Fix anchor, String prefix, String suffix) {
        if (!configured()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                worker.execute(() -> {
                    try {
                        Integer count = jdbc.queryForObject("""
                                SELECT COUNT(*) FROM member
                                WHERE id = ? AND pair_id = ? AND journey_enabled = TRUE AND sharing = TRUE
                                """, Integer.class, sender, pair);
                        if (count == null || count == 0) return;
                        String label = lookup(anchor);
                        if (!label.isEmpty()) jdbc.update("""
                                UPDATE companion_event SET body = ?
                                WHERE pair_id = ? AND sender_id = ? AND kind = ? AND created_at = ?
                                AND EXISTS (SELECT 1 FROM member WHERE id = ? AND pair_id = ? AND journey_enabled = TRUE AND sharing = TRUE)
                                """, prefix + label + "附近" + suffix, pair, sender, kind, at, sender, pair);
                    } catch (IOException | RuntimeException unavailable) {
                        // The generic event remains available when the optional provider fails.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        });
    }

    private String lookup(JourneyDetector.Fix anchor) throws IOException, InterruptedException {
        String location = String.format(Locale.ROOT, "%.4f,%.4f", anchor.longitude(), anchor.latitude());
        JsonNode result = request("/v3/assistant/coordinate/convert", "locations=" + location + "&coordsys=gps");
        String converted = result.path("locations").asText("");
        if (!converted.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) return "";
        return areaName(request("/v3/geocode/regeo", "location=" + converted + "&extensions=base"));
    }

    private JsonNode request(String path, String query) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(BASE + path + "?key=" + key + "&" + query))
                .timeout(TIMEOUT).GET().build();
        var reply = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode() != 200 || reply.body().length() > 64 * 1024) return json.nullNode();
        var response = json.readTree(reply.body());
        return response != null && "1".equals(response.path("status").asText()) ? response : json.nullNode();
    }

    static String areaName(JsonNode result) {
        var component = result.path("regeocode").path("addressComponent");
        for (JsonNode option : new JsonNode[]{component.path("neighborhood").path("name"),
                component.path("township"), component.path("district")}) {
            String label = option.asText("").strip();
            if (!label.isEmpty() && label.length() <= 80 && label.codePoints().noneMatch(Character::isISOControl)) {
                return label;
            }
        }
        return "";
    }

    @PreDestroy void shutdown() { worker.shutdownNow(); }
}
