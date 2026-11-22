package app.tongxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:adminbootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "server.servlet.session.cookie.secure=false", "ADMIN_INITIAL_PASSWORD=integration-temp-password"
})
class AdminBootstrapIntegrationTest {
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @Test
    void firstLoginGoesStraightToPasswordChange() throws Exception {
        assertThat(token().path("enabled").asBoolean()).isTrue();
        assertThat(login("123456")).isEqualTo(401);
        assertThat(login("integration-temp-password")).isEqualTo(204);
        assertThat(json.readTree(send("GET", "/api/session", "", null).body()).path("mustChangePassword").asBoolean()).isTrue();
        assertThat(send("GET", "/api/accounts", "", null).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/api/password", "{\"currentPassword\":\"integration-temp-password\",\"newPassword\":\"short\"}", token()).statusCode())
                .isEqualTo(400);
        assertThat(send("PUT", "/api/password",
                "{\"currentPassword\":\"integration-temp-password\",\"newPassword\":\"integration-test-new-admin-password\"}", token()).statusCode())
                .isEqualTo(204);
        assertThat(send("GET", "/api/session", "", null).statusCode()).isEqualTo(401);
        assertThat(login("integration-temp-password")).isEqualTo(401);
        assertThat(login("integration-test-new-admin-password")).isEqualTo(204);
        assertThat(send("GET", "/api/accounts", "", null).statusCode()).isEqualTo(200);
    }

    private int login(String password) throws Exception {
        return send("POST", "/login", "username=admin&password=" + password, token()).statusCode();
    }

    private JsonNode token() throws Exception {
        return json.readTree(send("GET", "/csrf", "", null).body());
    }

    private HttpResponse<String> send(String method, String path, String body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin" + path))
                .header("Content-Type", path.equals("/login") ? "application/x-www-form-urlencoded" : "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) request.header(csrf.path("header").asText(), csrf.path("token").asText());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
