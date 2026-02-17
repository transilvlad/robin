package com.mimecast.robin.smtp.webhook;

import com.mimecast.robin.config.server.WebhookConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.verb.Verb;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebhookCaller.
 */
@Isolated
class WebhookCallerTest {

    private static HttpServer mockServer;
    private static int mockServerPort;
    private Connection connection;
    private Verb verb;
    private Path tempEmailFile;

    @BeforeAll
    static void beforeAll() throws ConfigurationException, IOException {
        Foundation.init("src/test/resources/cfg/");

        // Start mock HTTP server.
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServerPort = mockServer.getAddress().getPort();
        mockServer.start();
    }

    @AfterAll
    static void afterAll() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // Create test connection with session and envelope.
        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .addRcpt("recipient@example.com");
        session.addEnvelope(envelope);
        connection = new Connection(session);

        // Create test verb.
        verb = new Verb("MAIL FROM:<sender@example.com>");

        // Create temporary email file for RAW tests.
        tempEmailFile = Files.createTempFile("test-email", ".eml");
        Files.writeString(tempEmailFile,
                "From: sender@example.com\r\n" +
                        "To: recipient@example.com\r\n" +
                        "Subject: Test Email\r\n" +
                        "\r\n" +
                        "This is a test email body.\r\n");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Reset server contexts.
        try {
            mockServer.removeContext("/webhook");
        } catch (IllegalArgumentException ignored) {
            // Context doesn't exist, ignore.
        }
        try {
            mockServer.removeContext("/raw-webhook");
        } catch (IllegalArgumentException ignored) {
            // Context doesn't exist, ignore.
        }

        if (tempEmailFile != null && Files.exists(tempEmailFile)) {
            Files.delete(tempEmailFile);
        }
    }

    private WebhookConfig createBasicConfig(String url, String method) {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", method);
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        configMap.put("includeSession", true);
        configMap.put("includeEnvelope", true);
        configMap.put("includeVerb", true);
        return new WebhookConfig(configMap);
    }

    @Test
    void testCallWithDisabledWebhook() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertEquals("", response.getBody());
    }

    @Test
    void testCallSyncPostSuccess() {
        // Setup mock server endpoint.
        mockServer.createContext("/webhook", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String responseBody = "{\"status\":\"success\"}";
                exchange.sendResponseHeaders(200, responseBody.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, 0);
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        WebhookConfig config = createBasicConfig(url, "POST");

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("success"));
    }

    @Test
    void testCallSyncGetSuccess() {
        // Setup mock server endpoint.
        mockServer.createContext("/webhook", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String responseBody = "{\"status\":\"ok\"}";
                exchange.sendResponseHeaders(200, responseBody.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, 0);
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "GET");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void testCallWithError() {
        // Setup mock server endpoint that returns error.
        mockServer.createContext("/webhook", exchange -> {
            String responseBody = "{\"error\":\"something went wrong\"}";
            exchange.sendResponseHeaders(500, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        WebhookConfig config = createBasicConfig(url, "POST");

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertFalse(response.isSuccess());
        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("error"));
    }

    @Test
    void testCallWithIgnoreErrors() {
        // Setup mock server endpoint that returns error.
        mockServer.createContext("/webhook", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", true);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        // With ignoreErrors, even a 500 should be treated as failure but caught.
        assertFalse(response.isSuccess());
        assertEquals(500, response.getStatusCode());
    }

    @Test
    void testCallAsync() throws InterruptedException {
        AtomicBoolean called = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Setup mock server endpoint.
        mockServer.createContext("/webhook", exchange -> {
            called.set(true);
            latch.countDown();
            String responseBody = "{\"status\":\"async success\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", false); // Async mode.
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        // Async returns immediately with success.
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());

        // Wait for actual call to complete.
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(called.get());
    }

    @Test
    void testCallWithBasicAuthentication() {
        AtomicReference<String> authHeader = new AtomicReference<>();

        // Setup mock server endpoint that captures auth header.
        mockServer.createContext("/webhook", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String responseBody = "{\"status\":\"authenticated\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("authType", "basic");
        configMap.put("authValue", "username:password");
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertNotNull(authHeader.get());
        assertTrue(authHeader.get().startsWith("Basic "));
    }

    @Test
    void testCallWithBearerAuthentication() {
        AtomicReference<String> authHeader = new AtomicReference<>();

        // Setup mock server endpoint that captures auth header.
        mockServer.createContext("/webhook", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String responseBody = "{\"status\":\"authenticated\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("authType", "bearer");
        configMap.put("authValue", "mytoken123");
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertNotNull(authHeader.get());
        assertEquals("Bearer mytoken123", authHeader.get());
    }

    @Test
    void testCallWithCustomHeaders() {
        AtomicReference<String> customHeader = new AtomicReference<>();

        // Setup mock server endpoint that captures custom header.
        mockServer.createContext("/webhook", exchange -> {
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Custom-Header"));
            String responseBody = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-Header", "CustomValue");
        configMap.put("headers", headers);

        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertEquals("CustomValue", customHeader.get());
    }

    @Test
    void testExtractSmtpResponseWithValidJson() {
        String body = "{\"smtpResponse\":\"250 OK\"}";
        String smtpResponse = WebhookCaller.extractSmtpResponse(body);
        assertEquals("250 OK", smtpResponse);
    }

    @Test
    void testExtractSmtpResponseWithInvalidJson() {
        String body = "not a json";
        String smtpResponse = WebhookCaller.extractSmtpResponse(body);
        assertNull(smtpResponse);
    }

    @Test
    void testExtractSmtpResponseWithEmptyBody() {
        String smtpResponse = WebhookCaller.extractSmtpResponse("");
        assertNull(smtpResponse);
    }

    @Test
    void testExtractSmtpResponseWithNullBody() {
        String smtpResponse = WebhookCaller.extractSmtpResponse(null);
        assertNull(smtpResponse);
    }

    @Test
    void testExtractSmtpResponseWithoutSmtpResponseField() {
        String body = "{\"status\":\"success\"}";
        String smtpResponse = WebhookCaller.extractSmtpResponse(body);
        assertNull(smtpResponse);
    }

    @Test
    void testCallRawWithDisabled() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void testCallRawSyncSuccess() {
        AtomicReference<String> receivedContent = new AtomicReference<>();

        // Setup mock server endpoint for RAW webhook.
        mockServer.createContext("/raw-webhook", exchange -> {
            // Read the body.
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedContent.set(new String(body, StandardCharsets.UTF_8));

            String responseBody = "{\"status\":\"received\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        configMap.put("base64", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(receivedContent.get());
        assertTrue(receivedContent.get().contains("Test Email"));
    }

    @Test
    void testCallRawWithBase64Encoding() {
        AtomicReference<String> receivedContent = new AtomicReference<>();

        // Setup mock server endpoint for RAW webhook.
        mockServer.createContext("/raw-webhook", exchange -> {
            // Read the body.
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedContent.set(new String(body, StandardCharsets.UTF_8));

            String responseBody = "{\"status\":\"received\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        configMap.put("base64", true);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(receivedContent.get());

        // Verify content is base64 encoded by decoding it.
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(receivedContent.get());
            String decodedContent = new String(decoded, StandardCharsets.UTF_8);
            assertTrue(decodedContent.contains("Test Email"), "Decoded content should contain 'Test Email'");
        } catch (IllegalArgumentException e) {
            fail("Content is not valid base64: " + e.getMessage());
        }
    }

    @Test
    void testCallRawAsync() throws InterruptedException {
        AtomicBoolean called = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Setup mock server endpoint.
        mockServer.createContext("/raw-webhook", exchange -> {
            called.set(true);
            latch.countDown();

            byte[] body = exchange.getRequestBody().readAllBytes();

            String responseBody = "{\"status\":\"async received\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", false); // Async mode.
        configMap.put("base64", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        // Async returns immediately with success.
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());

        // Wait for actual call to complete.
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(called.get());
    }

    @Test
    void testCallRawWithAuthentication() {
        AtomicReference<String> authHeader = new AtomicReference<>();

        // Setup mock server endpoint.
        mockServer.createContext("/raw-webhook", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            byte[] body = exchange.getRequestBody().readAllBytes();

            String responseBody = "{\"status\":\"authenticated\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("authType", "bearer");
        configMap.put("authValue", "rawtoken456");
        configMap.put("base64", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertTrue(response.isSuccess());
        assertNotNull(authHeader.get());
        assertEquals("Bearer rawtoken456", authHeader.get());
    }

    @Test
    void testCallRawWithCustomHeaders() {
        AtomicReference<String> customHeader = new AtomicReference<>();

        // Setup mock server endpoint.
        mockServer.createContext("/raw-webhook", exchange -> {
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Raw-Header"));

            byte[] body = exchange.getRequestBody().readAllBytes();

            String responseBody = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("base64", false);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Raw-Header", "RawValue");
        configMap.put("headers", headers);

        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertTrue(response.isSuccess());
        assertEquals("RawValue", customHeader.get());
    }

    @Test
    void testCallRawWithError() {
        // Setup mock server endpoint that returns error.
        mockServer.createContext("/raw-webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();

            String responseBody = "{\"error\":\"raw processing failed\"}";
            exchange.sendResponseHeaders(500, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        configMap.put("base64", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        assertFalse(response.isSuccess());
        assertEquals(500, response.getStatusCode());
    }

    @Test
    void testCallRawWithIgnoreErrors() {
        // Setup mock server endpoint that returns error.
        mockServer.createContext("/raw-webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", true);
        configMap.put("base64", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, tempEmailFile.toString(), new ConnectionMock());

        // With ignoreErrors, the call is considered successful at this level.
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, true",
            "PUT, true",
            "GET, false"
    })
    void testDifferentHttpMethods(String method, boolean expectsBody) {
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicReference<String> receivedMethod = new AtomicReference<>();

        // Setup mock server endpoint.
        mockServer.createContext("/webhook", exchange -> {
            requestCount.incrementAndGet();
            receivedMethod.set(exchange.getRequestMethod());

            if (expectsBody) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                assertTrue(body.length > 0);
            }

            String responseBody = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", method);
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertTrue(response.isSuccess());
        assertEquals(1, requestCount.get());
        assertEquals(method, receivedMethod.get());
    }

    @Test
    void testWebhookResponseContainer() {
        WebhookResponse response = new WebhookResponse(200, "test body", true);

        assertEquals(200, response.getStatusCode());
        assertEquals("test body", response.getBody());
        assertTrue(response.isSuccess());
    }

    @Test
    void testCallWithNonExistentServer() {
        String url = "http://localhost:9999/nonexistent";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 1000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.call(config, connection, verb);

        assertFalse(response.isSuccess());
        assertEquals(500, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCallRawWithNonExistentFile() {
        mockServer.createContext("/raw-webhook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        String url = "http://localhost:" + mockServerPort + "/raw-webhook";
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("url", url);
        configMap.put("method", "POST");
        configMap.put("timeout", 5000);
        configMap.put("waitForResponse", true);
        configMap.put("ignoreErrors", false);
        WebhookConfig config = new WebhookConfig(configMap);

        WebhookResponse response = WebhookCaller.callRaw(config, "/nonexistent/file.eml", new ConnectionMock());

        assertFalse(response.isSuccess());
        assertEquals(500, response.getStatusCode());
    }

    @Test
    void testExtractSmtpResponseValidJson() {
        String body = "{\"smtpResponse\":\"250 OK\", \"other\":123}";
        String result = WebhookCaller.extractSmtpResponse(body);
        assertEquals("250 OK", result, "Should extract smtpResponse from valid JSON");
    }

    @Test
    void testExtractSmtpResponseHtmlIgnored() {
        String body = "<html><body>Error 500</body></html>";
        String result = WebhookCaller.extractSmtpResponse(body);
        assertNull(result, "HTML should not be parsed as JSON");
    }

    @Test
    void testExtractSmtpResponsePlainTextIgnored() {
        String body = "Some plain text error message";
        String result = WebhookCaller.extractSmtpResponse(body);
        assertNull(result, "Plain text should not be parsed as JSON");
    }

    @Test
    void testExtractSmtpResponseMalformedJsonLenient() {
        // Missing closing brace but still starts/ends with braces after trimming added char; heuristic will try parse and fail.
        String body = "{\"smtpResponse\":\"250 OK\""; // malformed
        String result = WebhookCaller.extractSmtpResponse(body);
        assertNull(result, "Malformed JSON should return null");
    }

    @Test
    void testExtractSmtpResponseJsonWithoutField() {
        String body = "{\"message\":\"Hello\"}";
        String result = WebhookCaller.extractSmtpResponse(body);
        assertNull(result, "JSON without smtpResponse should return null");
    }
}
