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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for JSON-capable store endpoint.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointStoreJsonTest {

    private static final int TEST_PORT = 8097;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;

    private HttpClient httpClient;
    private Gson gson;
    private Path storageRoot;
    private Path testRoot;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        ApiEndpoint apiEndpoint = new ApiEndpoint();

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "none");
        storageRoot = Files.createTempDirectory("robin-store-json-");
        configMap.put("storagePath", storageRoot.toString());

        apiEndpoint.start(new EndpointConfig(configMap));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient = HttpClient.newHttpClient();
        gson = new Gson();

        testRoot = storageRoot.resolve("api-store-json-test");
        Files.createDirectories(testRoot);
        Files.createDirectories(testRoot.resolve("inbox"));
        Files.createDirectories(testRoot.resolve("empty-folder"));
        Files.createDirectories(testRoot.resolve("new"));
        Files.createDirectories(testRoot.resolve("cur"));
        Files.createDirectories(testRoot.resolve("tmp"));
        Files.writeString(testRoot.resolve("inbox/test.eml"), "Subject: API Test\n\nHello JSON", StandardCharsets.UTF_8);
        Files.writeString(testRoot.resolve("ignore.txt"), "ignore", StandardCharsets.UTF_8);
    }

    @AfterAll
    void tearDown() {
        try {
            if (storageRoot != null && Files.exists(storageRoot)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(storageRoot)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Test
    void testStoreDirectoryJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/store/api-store-json-test"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(response.body(), Map.class);
        assertEquals("/api-store-json-test", map.get("path"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) map.get("items");
        assertTrue(items.stream().anyMatch(i -> "dir".equals(i.get("type")) && "inbox".equals(i.get("name"))));
        assertTrue(items.stream().anyMatch(i -> "dir".equals(i.get("type")) && "empty-folder".equals(i.get("name"))));
        assertTrue(items.stream().anyMatch(i -> "dir".equals(i.get("type")) && "new".equals(i.get("name"))));
        assertTrue(items.stream().anyMatch(i -> "dir".equals(i.get("type")) && "cur".equals(i.get("name"))));
        assertTrue(items.stream().anyMatch(i -> "dir".equals(i.get("type")) && "tmp".equals(i.get("name"))));
        assertTrue(items.stream().noneMatch(i -> "ignore.txt".equals(i.get("name"))));
    }

    @Test
    void testStoreEmlJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/store/api-store-json-test/inbox/test.eml"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(response.body(), Map.class);
        assertEquals("test.eml", map.get("name"));
        assertTrue(String.valueOf(map.get("content")).contains("Subject: API Test"));
    }

    @Test
    void testStoreEmlTextFallback() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/store/api-store-json-test/inbox/test.eml"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/plain"));
        assertTrue(response.body().contains("Subject: API Test"));
    }

}
