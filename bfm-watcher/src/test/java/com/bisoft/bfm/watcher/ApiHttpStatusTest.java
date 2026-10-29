package com.bisoft.bfm.watcher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The API in a real servlet container. A script calling it must get a status and a JSON body, never a redirect
 * to the console's login page: the console chain owns {@code /error}, so an API error that went out through
 * {@code sendError} used to come back as 302 and an HTML page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("apitest")
class ApiHttpStatusTest {

    @BeforeAll
    static void manualLoopAndToken() throws IOException {
        System.setProperty("bfm.watcher.manual", "true");
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "bfm-apitest-token"), "t\n");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> call(String method, String path, String user, String password) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{\"maintenance\":true}"))
                .header("Content-Type", "application/json");
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void withoutCredentialsTheApiAnswers401AsJson() throws Exception {
        HttpResponse<String> r = call("GET", "/api/v1/cluster", null, null);
        assertEquals(401, r.statusCode(), "headers: " + r.headers().map());
        assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"), r.headers().toString());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("json"), r.headers().toString());
        assertTrue(r.body().contains("authentication required"), r.body());
        assertTrue(r.headers().firstValue("Location").isEmpty(), "answered with a redirect: " + r.headers().map());
    }

    @Test
    void wrongPasswordAlsoAnswers401AsJson() throws Exception {
        HttpResponse<String> r = call("GET", "/api/v1/cluster", "admin", "wrong");
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("authentication required"), r.body());
    }

    @Test
    void aViewerChangingSomethingGets403AsJson() throws Exception {
        HttpResponse<String> r = call("PUT", "/api/v1/controls", "viewer", "viewer-pw");
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("json"), r.headers().toString());
        assertTrue(r.body().contains("may only read"), r.body());
    }

    @Test
    void anAdminReadsAndWrites() throws Exception {
        HttpResponse<String> read = call("GET", "/api/v1/cluster", "admin", "admin-pw");
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"cluster\":\"prod-main\""), read.body());
        // The agents are unreachable here, so there is no cluster state yet and the switch cannot be set.
        HttpResponse<String> write = call("PUT", "/api/v1/controls", "admin", "admin-pw");
        assertEquals(409, write.statusCode(), write.body());
        assertTrue(write.body().contains("not created yet"), write.body());
    }

    @Test
    void theConsoleStillRedirectsToItsLoginPage() throws Exception {
        HttpResponse<String> r = call("GET", "/", null, null);
        assertEquals(302, r.statusCode());
        assertNotNull(r.headers().firstValue("Location").orElse(null));
        assertTrue(r.headers().firstValue("Location").get().endsWith("/login"), r.headers().toString());
    }
}
