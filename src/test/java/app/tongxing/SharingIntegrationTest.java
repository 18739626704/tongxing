package app.tongxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:tongxing;MODE=MySQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
        "server.servlet.session.cookie.secure=false",
        "tongxing.enrollment-key=integration-test-enrollment-key",
        "AMAP_ANDROID_KEY=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "AMAP_WEB_SERVICE_KEY=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
})
class SharingIntegrationTest {
    private static final String PASSWORD = "integration-test-password";
    private static final String ENROLLMENT = "integration-test-enrollment-key";
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyAuthenticatedClientsReceiveTheAndroidMapConfiguration() throws Exception {
        Client client = new Client();
        assertThat(client.get("/overview").statusCode()).isEqualTo(401);
        client.signIn("Map_config");
        var overview = client.overview();
        assertThat(overview.path("mapConfig").path("amapAndroidKey").asText())
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(overview.path("mapConfig").path("placeNamesAvailable").asBoolean()).isTrue();
        assertThat(overview.toString()).doesNotContain("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    @Test
    void pairingConsentPositionHistoryAndRevocation() throws Exception {
        Client alice = new Client();
        Client bob = new Client();
        Client stranger = new Client();
        assertThat(alice.get("/overview").statusCode()).isEqualTo(401);
        assertThat(alice.rawPost("/register").statusCode()).isEqualTo(403);
        alice.signIn("Alice");
        bob.signIn("Bob");
        stranger.signIn("Stranger");

        String code = alice.mutate("POST", "/invite", "", 200).path("code").asText();
        alice.mutate("POST", "/pair", Map.of("code", code), 409);
        bob.mutate("POST", "/pair", Map.of("code", code), 204);
        stranger.mutate("POST", "/pair", Map.of("code", code), 409);
        assertThat(alice.overview().at("/partner/displayName").asText()).isEqualTo("Bob");
        assertThat(alice.overview().at("/partner/sharing").asBoolean()).isFalse();

        String aliceKey = alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        String bobKey = bob.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        long now = System.currentTimeMillis();
        Map<String, Object> sample = sample(aliceKey, 34.7600, now);
        alice.mutate("POST", "/positions", sample, 204);
        alice.mutate("POST", "/positions", sample, 204);
        alice.mutate("POST", "/positions", sample(aliceKey, 34.7500, now - 5000), 204);
        bob.mutate("POST", "/positions", sample(bobKey, 34.7700, now), 204);
        assertThat(alice.overview().at("/partner/lastLocation/latitude").asDouble()).isEqualTo(34.7700);
        assertThat(bob.overview().at("/partner/lastLocation/latitude").asDouble()).isEqualTo(34.7600);
        assertThat(bob.overview().at("/partner/lastLocation/battery").asInt()).isEqualTo(76);
        JsonNode history = bob.route(true, now - 10_000, now + 10_000);
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("latitude").asDouble()).isEqualTo(34.7500);
        assertThat(stranger.overview().path("partner").isNull()).isTrue();
        assertThat(stranger.route(true, now - 10_000, now + 10_000).size()).isZero();

        alice.mutate("PUT", "/sharing", Map.of("enabled", false), 200);
        alice.mutate("POST", "/positions", sample(aliceKey, 34.7800, now), 409);
        assertThat(bob.overview().at("/partner/lastLocation").isNull()).isTrue();
        assertThat(bob.route(true, now - 10_000, now + 10_000).size()).isZero();
        assertThat(alice.route(false, now - 10_000, now + 10_000).size()).isEqualTo(2);

        String replacement = alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200)
                .path("shareKey").asText();
        assertThat(replacement).isNotEqualTo(aliceKey);
        alice.mutate("POST", "/positions", sample(aliceKey, 34.7800, now), 409);
        alice.mutate("POST", "/positions", sample(replacement, 200, now), 400);
        alice.mutate("DELETE", "/positions", "", 204);
        assertThat(alice.route(false, now - 10_000, now + 10_000).size()).isZero();

        bob.mutate("DELETE", "/pair", "", 204);
        assertThat(alice.overview().path("partner").isNull()).isTrue();
        assertThat(bob.route(false, now - 10_000, now + 10_000).size()).isZero();
        String rebind = alice.mutate("POST", "/invite", "", 200).path("code").asText();
        bob.mutate("POST", "/pair", Map.of("code", rebind), 204);
        bob.mutate("PUT", "/sharing", Map.of("enabled", true), 200);
        assertThat(alice.route(true, now - 10_000, now + 10_000).size()).isZero();
        bob.mutate("POST", "/logout", "", 204);
        assertThat(bob.get("/overview").statusCode()).isEqualTo(401);
    }

    @Test
    void inviteIsConsumedByOnlyOneConcurrentApplicant() throws Exception {
        Client inviter = new Client();
        Client first = new Client();
        Client second = new Client();
        inviter.signIn("Inviter");
        first.signIn("First");
        second.signIn("Second");
        String code = inviter.mutate("POST", "/invite", "", 200).path("code").asText();
        var result1 = CompletableFuture.supplyAsync(() -> first.accept(code));
        var result2 = CompletableFuture.supplyAsync(() -> second.accept(code));
        assertThat(new int[]{result1.get(), result2.get()}).containsExactlyInAnyOrder(204, 409);
    }

    @Test
    void companionActivityRespectsPairingLocationConsentAndAccountDeletion() throws Exception {
        Client alice = new Client();
        Client bob = new Client();
        Client outsider = new Client();
        alice.signIn("Alice");
        bob.signIn("Bob");
        outsider.signIn("Outsider");
        outsider.mutate("POST", "/sos", "", 409);
        String invite = alice.mutate("POST", "/invite", "", 200).path("code").asText();
        bob.mutate("POST", "/pair", Map.of("code", invite), 204);
        alice.mutate("POST", "/messages", Map.of("text", "到家告诉我"), 204);
        alice.mutate("POST", "/reminders", "", 204);
        alice.mutate("POST", "/sos", "", 204);
        assertThat(outsider.feed().path("events").size()).isZero();
        assertThat(bob.feed().at("/events/0/kind").asText()).isEqualTo("SOS");
        assertThat(bob.feed().at("/events/2/text").asText()).isEqualTo("到家告诉我");

        alice.mutate("POST", "/places", Map.of("name", "家", "latitude", 34.76, "longitude", 113.65, "radius", 250), 410);
        alice.mutate("PUT", "/usage-sharing", Map.of("enabled", true), 204);
        String key = alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        long time = System.currentTimeMillis();
        alice.mutate("POST", "/positions", sample(key, 34.77, time - 2000), 204);
        var arrival = new java.util.HashMap<>(sample(key, 34.76, time - 1000));
        arrival.put("screenDate", "2026-11-21");
        arrival.put("screenMinutes", 73);
        alice.mutate("POST", "/positions", arrival, 204);
        assertThat(bob.feed().at("/events/0/kind").asText()).isEqualTo("SOS");
        assertThat(bob.overview().at("/partner/screenMinutes").asInt()).isEqualTo(73);
        alice.mutate("POST", "/positions", sample(key, 34.77, time), 204);
        assertThat(bob.feed().at("/events/0/kind").asText()).isEqualTo("SOS");
        alice.mutate("PUT", "/usage-sharing", Map.of("enabled", false), 204);
        assertThat(bob.overview().at("/partner/screenMinutes").isNull()).isTrue();
        alice.mutate("DELETE", "/account", Map.of("password", "wrong-password"), 403);
        alice.mutate("DELETE", "/account", Map.of("password", PASSWORD), 204);
        assertThat(bob.overview().path("partner").isNull()).isTrue();
        assertThat(bob.feed().path("events").size()).isZero();
        assertThat(alice.get("/overview").statusCode()).isEqualTo(401);
    }

    @Test
    void resumesOnlyActiveSharingAndRestrictsOptionalReportsToConsentingPartners() throws Exception {
        Client alice = new Client();
        Client bob = new Client();
        Client outsider = new Client();
        alice.signIn("Alice");
        bob.signIn("Bob");
        outsider.signIn("Outsider");
        String invite = alice.mutate("POST", "/invite", "", 200).path("code").asText();
        bob.mutate("POST", "/pair", Map.of("code", invite), 204);

        String currentPair = alice.overview().path("pairId").asText();
        String wrongPair = UUID.randomUUID().toString();
        alice.mutate("PUT", "/sharing", Map.of("enabled", true, "pairId", wrongPair), 409);
        alice.mutate("PUT", "/app-usage-sharing", Map.of("enabled", true, "pairId", wrongPair), 409);
        alice.mutate("PUT", "/device-status-sharing", Map.of("enabled", true, "pairId", wrongPair), 409);
        assertThat(currentPair).isEqualTo(bob.overview().path("pairId").asText());
        assertThat(alice.overview().at("/self/appUsageEnabled").asBoolean()).isFalse();
        outsider.mutate("POST", "/routine", Map.of("resting", true), 409);
        alice.mutate("POST", "/routine", Map.of("resting", true), 204);
        assertThat(bob.feed().at("/events/0/kind").asText()).isEqualTo("ROUTINE");
        assertThat(bob.feed().at("/events/0/text").asText()).isEqualTo("开始休息");
        assertThat(outsider.feed().path("events").isEmpty()).isTrue();

        assertThat(alice.get("/sharing").statusCode()).isEqualTo(409);
        Map<String, Object> enableForPair = Map.of("enabled", true, "pairId", currentPair);
        String key = alice.mutate("PUT", "/sharing", enableForPair, 200).path("shareKey").asText();
        assertThat(alice.mutate("PUT", "/sharing", enableForPair, 200).path("shareKey").asText()).isEqualTo(key);
        var resumed = alice.get("/sharing");
        assertThat(resumed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(resumed.body()).path("shareKey").asText()).isEqualTo(key);
        assertThat(bob.get("/sharing").statusCode()).isEqualTo(409);

        long now = System.currentTimeMillis();
        String date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        Map<String, Object> report = Map.of("shareKey", key, "date", date,
                "recordedAt", now, "apps", java.util.List.of(Map.of("name", "阅读", "seconds", 120)));
        alice.mutate("PUT", "/app-usage", report, 409);
        assertThat(bob.get("/app-usage").statusCode()).isEqualTo(404);
        alice.mutate("PUT", "/app-usage-sharing", Map.of("enabled", true), 204);
        alice.mutate("PUT", "/app-usage", report, 204);
        assertThat(outsider.get("/app-usage").statusCode()).isEqualTo(404);
        assertThat(json.readTree(bob.get("/app-usage").body()).at("/apps/0/seconds").asInt()).isEqualTo(120);

        var position = new java.util.HashMap<>(sample(key, 34.76, now));
        position.put("deviceModel", "Test Phone");
        position.put("networkType", "WIFI");
        position.put("unlockedAt", now - 1000);
        alice.mutate("POST", "/positions", position, 204);
        assertThat(bob.overview().at("/partner/device").isNull()).isTrue();
        alice.mutate("PUT", "/device-status-sharing", Map.of("enabled", true), 204);
        position.put("id", UUID.randomUUID().toString());
        position.put("recordedAt", now + 1);
        alice.mutate("POST", "/positions", position, 204);
        assertThat(bob.overview().at("/partner/device/model").asText()).isEqualTo("Test Phone");
        assertThat(bob.overview().at("/partner/device/unlockedAt").asLong()).isEqualTo(now - 1000);
        alice.mutate("PUT", "/device-status-sharing", Map.of("enabled", false), 204);
        assertThat(bob.overview().at("/partner/device").isNull()).isTrue();

        alice.mutate("PUT", "/app-usage-sharing", Map.of("enabled", false), 204);
        assertThat(bob.get("/app-usage").statusCode()).isEqualTo(404);
        alice.mutate("PUT", "/app-usage-sharing", Map.of("enabled", true), 204);
        assertThat(json.readTree(bob.get("/app-usage").body()).path("apps").isEmpty()).isTrue();
        alice.mutate("PUT", "/sharing", Map.of("enabled", false), 200);
        assertThat(alice.get("/sharing").statusCode()).isEqualTo(409);
        assertThat(bob.get("/app-usage").statusCode()).isEqualTo(404);
        alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200);
        alice.mutate("PUT", "/app-usage", report, 409);
    }

    @Test
    void checkInsAndLowBatteryReachOnlyPartnerAndSosLocationRequiresLiveConsent() throws Exception {
        Client alice = new Client();
        Client bob = new Client();
        Client outsider = new Client();
        alice.signIn("Alice");
        bob.signIn("Bob");
        outsider.signIn("Outsider");
        String invite = alice.mutate("POST", "/invite", "", 200).path("code").asText();
        bob.mutate("POST", "/pair", Map.of("code", invite), 204);

        outsider.mutate("POST", "/check-ins", Map.of("kind", "GOOD_NIGHT"), 409);
        alice.mutate("POST", "/check-ins", Map.of("kind", "INVALID"), 400);
        alice.mutate("POST", "/check-ins", Map.of("kind", "ARRIVED"), 204);
        assertThat(bob.feed().at("/events/0/text").asText()).isEqualTo("我到了");

        long now = System.currentTimeMillis();
        String aliceKey = alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        var reading = new java.util.HashMap<>(sample(aliceKey, 34.76, now - 5000));
        reading.put("battery", 15);
        reading.put("charging", false);
        reading.put("networkType", "WIFI");
        alice.mutate("POST", "/positions", reading, 204);
        assertThat(bob.feed().at("/events/0/kind").asText()).isEqualTo("CHECK_IN");
        alice.mutate("PUT", "/device-status-sharing", Map.of("enabled", true), 204);
        reading.put("battery", 17);
        reading.put("id", UUID.randomUUID().toString());
        reading.put("recordedAt", now - 4000);
        alice.mutate("POST", "/positions", reading, 204);
        reading.put("battery", 14);
        reading.put("id", UUID.randomUUID().toString());
        reading.put("recordedAt", now - 3000);
        alice.mutate("POST", "/positions", reading, 204);
        assertThat(bob.feed().at("/events/0/text").asText()).isEqualTo("电量仅剩 14%");
        reading.put("id", UUID.randomUUID().toString());
        reading.put("recordedAt", now - 2000);
        alice.mutate("POST", "/positions", reading, 204);
        assertThat(bob.feed().path("events").size()).isEqualTo(2);

        alice.mutate("POST", "/sos", Map.of("includeLocation", true), 204);
        var help = bob.feed().at("/events/0");
        assertThat(help.path("hasLocation").asBoolean()).isTrue();
        assertThat(help.has("latitude")).isFalse();
        String url = "/sos/" + help.path("id").asLong();
        var location = bob.get(url);
        assertThat(location.statusCode()).isEqualTo(200);
        assertThat(json.readTree(location.body()).path("latitude").asDouble()).isEqualTo(34.76);
        assertThat(outsider.get(url).statusCode()).isEqualTo(404);
        alice.mutate("PUT", "/sharing", Map.of("enabled", false), 200);
        alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200);
        assertThat(bob.get(url).statusCode()).isEqualTo(404);
        assertThat(bob.feed().at("/events/0/hasLocation").asBoolean()).isFalse();

        String bobKey = bob.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        bob.mutate("POST", "/positions", sample(bobKey, 34.78, now - 360_000), 204);
        bob.mutate("POST", "/sos", Map.of("includeLocation", true), 204);
        assertThat(alice.feed().at("/events/0/hasLocation").asBoolean()).isFalse();
    }

    @Test
    void stopsLegacyFencesAndSharesOnlyCurrentOptedInScreenState() throws Exception {
        Client alice = new Client();
        Client bob = new Client();
        alice.signIn("Alice");
        bob.signIn("Bob");
        bob.mutate("POST", "/pair", Map.of("code", alice.mutate("POST", "/invite", "", 200).path("code").asText()), 204);
        assertThat(json.readTree(alice.get("/places").body()).isEmpty()).isTrue();
        String key = alice.mutate("PUT", "/sharing", Map.of("enabled", true), 200).path("shareKey").asText();
        Map<String, String> snapshot = Map.of("shareKey", key, "state", "UNLOCKED");
        alice.mutate("PUT", "/live-screen", snapshot, 409);
        alice.mutate("PUT", "/live-screen-sharing", Map.of("enabled", true), 204);
        alice.mutate("PUT", "/live-screen", snapshot, 204);
        assertThat(bob.overview().at("/partner/liveScreen/state").asText()).isEqualTo("UNLOCKED");
        assertThat(bob.feed().path("events").isEmpty()).isTrue();
        alice.mutate("PUT", "/live-screen-sharing", Map.of("enabled", false), 204);
        assertThat(bob.overview().at("/partner/liveScreen").isNull()).isTrue();

        alice.mutate("PUT", "/journey-sharing", Map.of("enabled", true), 204);
        long now = System.currentTimeMillis();
        // The detector accepts slightly future device times, allowing a full baseline to be tested without waiting.
        for (int offset : new int[]{-110, -50, 10, 70}) {
            alice.mutate("POST", "/positions", sample(key, 34.76, now + offset * 1000L), 204);
        }
        assertThat(bob.overview().at("/partner/journey/state").asText()).isEqualTo("STOPPED");
        assertThat(bob.feed().path("events").isEmpty()).isTrue();
        alice.mutate("PUT", "/journey-sharing", Map.of("enabled", false), 204);
        assertThat(bob.overview().at("/partner/journey").isNull()).isTrue();
    }

    private Map<String, Object> sample(String shareKey, double latitude, long time) {
        return Map.of("id", UUID.randomUUID().toString(), "shareKey", shareKey,
                "latitude", latitude, "longitude", 113.65, "accuracy", 8,
                "battery", 76, "charging", true, "recordedAt", time);
    }

    private final class Client {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build();

        void signIn(String name) throws Exception {
            String username = name.toLowerCase(java.util.Locale.ROOT) + UUID.randomUUID().toString().substring(0, 8);
            mutate("POST", "/register", Map.of("username", username,
                    "password", PASSWORD, "displayName", name, "enrollmentKey", ENROLLMENT), 201);
            HttpResponse<String> login = send("POST", "/login", "username=" + username + "&password=" + PASSWORD,
                    "application/x-www-form-urlencoded", token());
            assertThat(login.statusCode()).isEqualTo(204);
        }

        int accept(String code) {
            try {
                return send("POST", "/pair", json.writeValueAsString(Map.of("code", code)),
                        "application/json", token()).statusCode();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        JsonNode mutate(String method, String path, Object body, int status) throws Exception {
            HttpResponse<String> response = send(method, path, body instanceof String text
                    ? text : json.writeValueAsString(body), "application/json", token());
            assertThat(response.statusCode()).as("%s %s %s", method, path, response.body()).isEqualTo(status);
            return json.readTree(response.body());
        }

        JsonNode feed() throws Exception {
            return json.readTree(get("/feed").body());
        }

        JsonNode overview() throws Exception {
            HttpResponse<String> response = get("/overview");
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body());
        }

        JsonNode route(boolean partner, long from, long to) throws Exception {
            HttpResponse<String> response = get("/positions?partner=" + partner + "&from=" + from + "&to=" + to);
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body());
        }

        HttpResponse<String> rawPost(String path) throws Exception {
            return send("POST", path, "{}", "application/json", null);
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, "", "application/json", null);
        }

        private JsonNode token() throws Exception {
            return json.readTree(get("/csrf").body());
        }

        private HttpResponse<String> send(String method, String path, String body, String contentType, JsonNode token)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header(token.path("header").asText(), token.path("token").asText());
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
