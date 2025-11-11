package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for queue control API endpoints.
 * NOTE: This test can be run individually with: mvn test -Dtest=ApiEndpointQueueControlsIntegrationTest
 * It is excluded from the main test suite due to environment setup requirements.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiEndpointQueueControlsIntegrationTest {

    private static final int TEST_PORT = 8095;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private ApiEndpoint apiEndpoint;
    private HttpClient httpClient;
    private Gson gson;

    @BeforeAll
    void setUp() throws IOException {
        apiEndpoint = new ApiEndpoint();
        
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "none");
        
        EndpointConfig config = new EndpointConfig(configMap);
        apiEndpoint.start(config);
        
        // Give the server a moment to start.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        httpClient = HttpClient.newHttpClient();
        gson = new Gson();
        
        // Ensure the queue directory exists.
        java.io.File queueDir = RelayQueueCron.QUEUE_FILE.getParentFile();
        if (queueDir != null && !queueDir.exists()) {
            queueDir.mkdirs();
        }
    }

    @BeforeEach
    void clearQueue() {
        // Clear the queue before each test if possible.
        try {
            java.io.File queueDir = RelayQueueCron.QUEUE_FILE.getParentFile();
            if (queueDir != null && !queueDir.exists()) {
                queueDir.mkdirs();
            }
            
            if (queueDir != null && queueDir.canWrite()) {
                PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
                queue.clear();
            }
        } catch (Exception e) {
            // If the queue can't be cleared (permission issues), skip it.
            // Tests will still work as they create their own data.
        }
    }

    @Test
    void testQueueDeleteSingleItem() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
        queue.enqueue(new RelaySession(new Session().setUID("test-1")));
        queue.enqueue(new RelaySession(new Session().setUID("test-2")));
        queue.enqueue(new RelaySession(new Session().setUID("test-3")));
        
        assertEquals(3, queue.size());
        
        // Delete item at index 1 (test-2).
        Map<String, Object> payload = new HashMap<>();
        payload.put("index", 1);
        
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
        
        // Verify queue state.
        assertEquals(2, queue.size());
        assertEquals("test-1", queue.snapshot().get(0).getSession().getUID());
        assertEquals("test-3", queue.snapshot().get(1).getSession().getUID());
    }

    @Test
    void testQueueDeleteMultipleItems() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
        for (int i = 1; i <= 5; i++) {
            queue.enqueue(new RelaySession(new Session().setUID("test-" + i)));
        }
        
        assertEquals(5, queue.size());
        
        // Delete items at indices 1, 3 (test-2 and test-4).
        Map<String, Object> payload = new HashMap<>();
        payload.put("indices", new int[]{1, 3});
        
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
        
        // Verify queue state.
        assertEquals(3, queue.size());
        assertEquals("test-1", queue.snapshot().get(0).getSession().getUID());
        assertEquals("test-3", queue.snapshot().get(1).getSession().getUID());
        assertEquals("test-5", queue.snapshot().get(2).getSession().getUID());
    }

    @Test
    void testQueueRetrySingleItem() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
        RelaySession session = new RelaySession(new Session().setUID("retry-test"));
        queue.enqueue(session);
        
        assertEquals(1, queue.size());
        assertEquals(0, queue.snapshot().get(0).getRetryCount());
        
        // Retry item at index 0.
        Map<String, Object> payload = new HashMap<>();
        payload.put("index", 0);
        
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
        
        // Verify retry count was incremented.
        assertEquals(1, queue.size());
        assertEquals(1, queue.snapshot().get(0).getRetryCount());
    }

    @Test
    void testQueueBounceSingleItem() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
        queue.enqueue(new RelaySession(new Session().setUID("bounce-test")));
        queue.enqueue(new RelaySession(new Session().setUID("keep-test")));
        
        assertEquals(2, queue.size());
        
        // Bounce item at index 0.
        Map<String, Object> payload = new HashMap<>();
        payload.put("index", 0);
        
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
        
        // Verify item was removed.
        assertEquals(1, queue.size());
        assertEquals("keep-test", queue.snapshot().get(0).getSession().getUID());
    }

    @Test
    void testQueueListWithPagination() throws Exception {
        // Add 25 items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
        for (int i = 1; i <= 25; i++) {
            queue.enqueue(new RelaySession(new Session().setUID("item-" + i)));
        }
        
        assertEquals(25, queue.size());
        
        // Request page 2 with limit 10.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue-list?page=2&limit=10"))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        
        String html = response.body();
        
        // Verify pagination info is in the HTML.
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
        assertTrue(response.body().contains("Missing 'index' or 'indices' parameter"));
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
