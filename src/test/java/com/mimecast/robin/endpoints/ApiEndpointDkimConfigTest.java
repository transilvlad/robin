package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointDkimConfigTest {

    private static final int TEST_PORT = 8097;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private Path configRoot;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        configRoot = Files.createTempDirectory("robin-dkim-config-");
        ApiEndpoint apiEndpoint = new ApiEndpoint();

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "none");
        configMap.put("dkimConfigPath", configRoot.toString());
        apiEndpoint.start(new EndpointConfig(configMap));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    void tearDown() throws IOException {
        if (configRoot != null && Files.exists(configRoot)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(configRoot)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void testCreateDkimConfig() throws Exception {
        String body = """
                {
                  "domain":"example.com",
                  "selector":"202602r",
                  "privateKeyBase64":"ZmFrZS1rZXk=",
                  "algorithm":"RSA_2048"
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/config/dkim"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(response.body(), Map.class);
        assertEquals("ok", map.get("status"));
        assertEquals("example.com", map.get("domain"));
        assertEquals("202602r", map.get("selector"));

        Path file = configRoot.resolve("dkim").resolve("example.com.json5");
        assertTrue(Files.exists(file));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("domain: \"example.com\""));
        assertTrue(content.contains("selector: \"202602r\""));
        assertTrue(content.contains("algorithm: \"rsa\""));
        assertTrue(content.contains("privateKey: \"ZmFrZS1rZXk=\""));
    }

    @Test
    void testValidationErrors() throws Exception {
        String body = """
                {
                  "domain":"not a domain",
                  "selector":"202602r",
                  "privateKeyBase64":"ZmFrZS1rZXk="
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/config/dkim"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid domain"));
    }

    @Test
    void testMethodValidation() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/config/dkim"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }
}
