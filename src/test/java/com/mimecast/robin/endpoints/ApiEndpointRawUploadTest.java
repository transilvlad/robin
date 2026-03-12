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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for raw EML upload mode on client endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointRawUploadTest {

    private HttpClient httpClient;
    private Gson gson;
    private ApiEndpoint apiEndpoint;
    private String baseUrl;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        apiEndpoint = new ApiEndpoint();
        int testPort = findFreePort();
        baseUrl = "http://localhost:" + testPort;

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", testPort);
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

    @AfterAll
    void tearDown() {
        if (apiEndpoint != null) {
            apiEndpoint.stop();
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
                .uri(URI.create(baseUrl + "/client/queue?mail=tony@example.com&rcpt=pepper@example.com"))
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

        // Verify the uploaded file path is non-empty and ends with .eml.
        String uploadedFile = (String) map.get("uploadedFile");
        assertNotNull(uploadedFile);
        assertTrue(uploadedFile.endsWith(".eml"), "Uploaded file should have .eml extension");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
