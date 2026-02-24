package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for users API endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointUsersTest {

    private static final int TEST_PORT = 8096;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private HttpClient httpClient;
    private Gson gson;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        ApiEndpoint apiEndpoint = new ApiEndpoint();

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "none");

        apiEndpoint.start(new EndpointConfig(configMap));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient = HttpClient.newHttpClient();
        gson = new Gson();
    }

    @Test
    void testUsersList() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(response.body(), Map.class);

        assertEquals("config", map.get("source"));
        assertEquals(3.0, map.get("count"));

        @SuppressWarnings("unchecked")
        List<String> users = (List<String>) map.get("users");
        assertTrue(users.contains("tony@example.com"));
        assertTrue(users.contains("pepper@example.com"));
        assertTrue(users.contains("happy@example.com"));
    }

    @Test
    void testUserExists() throws Exception {
        HttpRequest existsRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users/tony%40example.com/exists"))
                .GET()
                .build();

        HttpResponse<String> existsResponse = httpClient.send(existsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, existsResponse.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> existsMap = gson.fromJson(existsResponse.body(), Map.class);
        assertEquals("config", existsMap.get("source"));
        assertEquals("tony@example.com", existsMap.get("username"));
        assertEquals(Boolean.TRUE, existsMap.get("exists"));

        HttpRequest notExistsRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users/missing%40example.com/exists"))
                .GET()
                .build();

        HttpResponse<String> notExistsResponse = httpClient.send(notExistsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, notExistsResponse.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> notExistsMap = gson.fromJson(notExistsResponse.body(), Map.class);
        assertEquals("missing@example.com", notExistsMap.get("username"));
        assertEquals(Boolean.FALSE, notExistsMap.get("exists"));
    }

    @Test
    void testUsersAuthenticate() throws Exception {
        String okBody = "{\"username\":\"tony@example.com\",\"password\":\"stark\"}";
        HttpRequest okRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users/authenticate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(okBody))
                .build();

        HttpResponse<String> okResponse = httpClient.send(okRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, okResponse.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> okMap = gson.fromJson(okResponse.body(), Map.class);
        assertEquals("tony@example.com", okMap.get("username"));
        assertEquals(Boolean.TRUE, okMap.get("authenticated"));

        String badBody = "{\"username\":\"tony@example.com\",\"password\":\"wrong\"}";
        HttpRequest badRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users/authenticate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(badBody))
                .build();

        HttpResponse<String> badResponse = httpClient.send(badRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, badResponse.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> badMap = gson.fromJson(badResponse.body(), Map.class);
        assertEquals(Boolean.FALSE, badMap.get("authenticated"));
    }

    @Test
    void testUsersMethodValidation() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/users/authenticate"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertFalse(response.body().isBlank());
    }
}
