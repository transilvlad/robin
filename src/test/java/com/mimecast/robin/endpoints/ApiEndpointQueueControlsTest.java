package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for queue control API endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiEndpointQueueControlsTest {

    private static final int TEST_PORT = 8095;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private ApiEndpoint apiEndpoint;
    private HttpClient httpClient;
    private Gson gson;
    private Path tmpDir;
    private File tmpQueueFile;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        // Initialize Foundation with test configuration
        Foundation.init("src/test/resources/cfg/");
        
        // Create a temporary directory and queue file for testing
        tmpDir = Files.createTempDirectory("robinRelayQueue-");
        tmpQueueFile = tmpDir.resolve("relayQueue-" + System.nanoTime() + ".db").toFile();
        
        // Override the queue file path in runtime config to use temp directory
        Config.getServer().getQueue().getMap().put("queueFile", tmpQueueFile.getAbsolutePath());
        
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
    }

    @BeforeEach
    void clearQueue() {
        // Clear the queue before each test.
        try {
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
            queue.clear();
        } catch (Exception e) {
            // Ignore errors during queue clearing
        }
    }

    @Test
    void testQueueDeleteSingleItem() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
        RelaySession rs1 = new RelaySession(new Session().setUID("test-1"));
        RelaySession rs2 = new RelaySession(new Session().setUID("test-2"));
        RelaySession rs3 = new RelaySession(new Session().setUID("test-3"));
        queue.enqueue(rs1);
        queue.enqueue(rs2);
        queue.enqueue(rs3);
        
        assertEquals(3, queue.size());
        
        // Delete item by UID (test-2).
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
        
        // Verify queue state.
        assertEquals(2, queue.size());
        assertEquals("test-1", queue.snapshot().get(0).getSession().getUID());
        assertEquals("test-3", queue.snapshot().get(1).getSession().getUID());
    }

    @Test
    void testQueueDeleteMultipleItems() throws Exception {
        // Add items to the queue.
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
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
        
        // Delete items by UIDs (test-2 and test-4).
        Map<String, Object> payload = new HashMap<>();
        payload.put("uids", new String[]{rs2.getUID(), rs4.getUID()});
        
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
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
        RelaySession session = new RelaySession(new Session().setUID("retry-test"));
        queue.enqueue(session);
        
        assertEquals(1, queue.size());
        assertEquals(0, queue.snapshot().get(0).getRetryCount());
        
        // Retry item by UID.
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", session.getUID());
        
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
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
        RelaySession rs1 = new RelaySession(new Session().setUID("bounce-test"));
        RelaySession rs2 = new RelaySession(new Session().setUID("keep-test"));
        queue.enqueue(rs1);
        queue.enqueue(rs2);
        
        assertEquals(2, queue.size());
        
        // Bounce item by UID.
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", rs1.getUID());
        
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
        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
        for (int i = 1; i <= 25; i++) {
            queue.enqueue(new RelaySession(new Session().setUID("item-" + i)));
        }
        
        assertEquals(25, queue.size());
        
        // Request page 2 with limit 10.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue/list?page=2&limit=10"))
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

    @AfterAll
    void tearDown() {
        // Close the queue and clean up temporary files
        try {
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(tmpQueueFile);
            queue.close();
        } catch (Exception ignored) {}
        
        // Delete queue file and any WAL segments
        try { tmpQueueFile.delete(); } catch (Exception ignored) {}
        for (int i = 0; i < 4; i++) {
            try { new File(tmpQueueFile.getAbsolutePath() + ".wal." + i).delete(); } catch (Exception ignored) {}
        }
        try { new File(tmpQueueFile.getAbsolutePath() + ".p").delete(); } catch (Exception ignored) {}
        
        // Delete temp directory
        try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
    }
}
