package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelaySession;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for raw EML upload mode on client endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointRawUploadTest {

    private static final int TEST_PORT = 8098;
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
    void testQueueRawUploadMessageRfc822() throws Exception {
        String eml = "From: tony@example.com\r\n" +
                "To: pepper@example.com\r\n" +
                "Subject: Raw upload test\r\n" +
                "\r\n" +
                "Hello from raw upload.\r\n";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/client/queue?mail=tony@example.com&rcpt=pepper@example.com"))
                .header("Content-Type", "message/rfc822")
                .POST(HttpRequest.BodyPublishers.ofString(eml, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(response.body(), Map.class);
        assertEquals("QUEUED", map.get("status"));
        assertNotNull(map.get("uploadedFile"));

        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        assertEquals(1, queue.size());

        RelaySession relaySession = queue.snapshot().getFirst();
        String envelopeFile = relaySession.getSession().getEnvelopes().getFirst().getFile();
        assertNotNull(envelopeFile);
        assertTrue(envelopeFile.contains("queue"));
        assertTrue(Files.exists(Path.of(envelopeFile)));
    }
}
