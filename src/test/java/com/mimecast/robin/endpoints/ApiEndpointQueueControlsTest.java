package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for queue control API endpoints.
 * <p>These tests use the singleton queue instance, so they must run serially.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointQueueControlsTest {

    private static final int TEST_PORT = 8095;
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

    @BeforeEach
    void clearQueue() {
        try {
            PersistentQueue.getInstance().clear();
        } catch (Exception ignored) {
        }
    }

    @Test
    void testQueueDeleteSingleItem() throws Exception {
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        RelaySession rs1 = new RelaySession(new Session().setUID("test-1"));
        RelaySession rs2 = new RelaySession(new Session().setUID("test-2"));
        RelaySession rs3 = new RelaySession(new Session().setUID("test-3"));
        queue.enqueue(rs1);
        queue.enqueue(rs2);
        queue.enqueue(rs3);

        assertEquals(3, queue.size());

        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", rs2.getUID());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = gson.fromJson(response.body(), Map.class);
        assertEquals("OK", result.get("status"));
        assertEquals(1.0, result.get("deletedCount"));
        assertEquals(2.0, result.get("queueSize"));

        assertEquals(2, queue.size());
        assertEquals("test-1", queue.snapshot().get(0).getSession().getUID());
        assertEquals("test-3", queue.snapshot().get(1).getSession().getUID());
    }

    @Test
    void testQueueDeleteMultipleItems() throws Exception {
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        RelaySession rs1 = new RelaySession(new Session().setUID("test-1"));
        RelaySession rs2 = new RelaySession(new Session().setUID("test-2"));
        RelaySession rs3 = new RelaySession(new Session().setUID("test-3"));
        RelaySession rs4 = new RelaySession(new Session().setUID("test-4"));
        RelaySession rs5 = new RelaySession(new Session().setUID("test-5"));
        queue.enqueue(rs1);
        queue.enqueue(rs2);
        queue.enqueue(rs3);
        queue.enqueue(rs4);
        queue.enqueue(rs5);

        assertEquals(5, queue.size());

        Map<String, Object> payload = new HashMap<>();
        payload.put("uids", java.util.Arrays.asList(rs2.getUID(), rs4.getUID()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = gson.fromJson(response.body(), Map.class);
        assertEquals("OK", result.get("status"));
        assertEquals(2.0, result.get("deletedCount"));
        assertEquals(3.0, result.get("queueSize"));

        assertEquals(3, queue.size());
        assertEquals("test-1", queue.snapshot().get(0).getSession().getUID());
        assertEquals("test-3", queue.snapshot().get(1).getSession().getUID());
        assertEquals("test-5", queue.snapshot().get(2).getSession().getUID());
    }

    @Test
    void testQueueRetrySingleItem() throws Exception {
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        RelaySession relay = new RelaySession(new Session().setUID("retry-test"));
        queue.enqueue(relay);

        assertEquals(1, queue.size());
        assertEquals(0, relay.getRetryCount());

        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", relay.getUID());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/retry"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = gson.fromJson(response.body(), Map.class);
        assertEquals("OK", result.get("status"));
        assertEquals(1.0, result.get("retriedCount"));
        assertEquals(1.0, result.get("queueSize"));

        assertEquals(1, queue.size());
        assertEquals(1, relay.getRetryCount());
    }

    @Test
    void testQueueBounceSingleItem() throws Exception {
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        RelaySession bounce = new RelaySession(new Session().setUID("bounce-test"));
        RelaySession keep = new RelaySession(new Session().setUID("keep-test"));
        queue.enqueue(bounce);
        queue.enqueue(keep);

        assertEquals(2, queue.size());

        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", bounce.getUID());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/bounce"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = gson.fromJson(response.body(), Map.class);
        assertEquals("OK", result.get("status"));
        assertEquals(1.0, result.get("bouncedCount"));
        assertEquals(1.0, result.get("queueSize"));

        assertEquals(1, queue.size());
        assertEquals(keep.getSession().getUID(), queue.snapshot().getFirst().getSession().getUID());
    }

    @Test
    void testQueueListWithPagination() throws Exception {
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        for (int i = 1; i <= 25; i++) {
            queue.enqueue(new RelaySession(new Session().setUID("item-" + i)));
        }

        assertEquals(25, queue.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/list?page=2&limit=10"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        String html = response.body();

        assertTrue(html.contains("Total items: <strong>25</strong>"));
        assertTrue(html.contains("Page <strong>2</strong>"));
        assertTrue(html.contains("Showing <strong>11</strong> to <strong>20</strong>"));
    }

    @Test
    void testQueueDeleteWithInvalidPayload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Missing 'uid' or 'uids' parameter"));
    }

    @Test
    void testQueueDeleteWithEmptyBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Empty request body"));
    }
}
