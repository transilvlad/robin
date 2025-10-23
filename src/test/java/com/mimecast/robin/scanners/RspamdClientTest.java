package com.mimecast.robin.scanners;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RspamdClient.
 * <p>
 * These tests use MockWebServer to simulate Rspamd API responses without requiring
 * a real Rspamd installation. The mock server responds to HTTP requests with predefined
 * JSON responses representing various spam/ham detection scenarios.
 */
class RspamdClientTest {

    private static final String SPAM_CONTENT = "This is a spam message with viagra and lottery keywords";
    private static final String HAM_CONTENT = "This is a legitimate email from a colleague";
    private static final String CUSTOM_HOST = "localhost";

    private MockWebServer mockWebServer;
    private RspamdClient client;
    private File cleanFile;
    private File spamFile;

    @BeforeEach
    void setUp() throws IOException {
        // Start the mock web server
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Initialize client pointing to the mock server
        int port = mockWebServer.getPort();
        client = new RspamdClient(CUSTOM_HOST, port);

        // Create test files
        cleanFile = File.createTempFile("rspamd-test-clean-", ".txt");
        Files.writeString(cleanFile.toPath(), HAM_CONTENT);

        spamFile = File.createTempFile("rspamd-test-spam-", ".txt");
        Files.writeString(spamFile.toPath(), SPAM_CONTENT);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Shutdown the mock server
        mockWebServer.shutdown();

        // Clean up test files
        if (cleanFile != null) Files.deleteIfExists(cleanFile.toPath());
        if (spamFile != null) Files.deleteIfExists(spamFile.toPath());
    }

    @Test
    void testConstructorWithDefaultValues() {
        RspamdClient defaultClient = new RspamdClient();
        assertNotNull(defaultClient);
    }

    @Test
    void testConstructorWithCustomValues() {
        RspamdClient customClient = new RspamdClient(CUSTOM_HOST, 11333);
        assertNotNull(customClient);
    }

    @Test
    void testPingWithServerAvailable() {
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody("pong"));

        boolean result = client.ping();
        assertTrue(result, "Rspamd server should be available");
    }

    @Test
    void testPingWithServerUnavailable() throws IOException {
        mockWebServer.shutdown();
        RspamdClient unavailableClient = new RspamdClient("non-existent-host", 9999);
        boolean result = unavailableClient.ping();
        assertFalse(result, "Rspamd server should not be available with invalid host/port");
    }

    @Test
    void testGetInfoWithServerAvailable() {
        String infoResponse = "{\"version\":\"2.1\",\"hostname\":\"test-host\"}";
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(infoResponse)
                .addHeader("Content-Type", "application/json"));

        var info = client.getInfo();
        assertTrue(info.isPresent(), "Info should be available when server is running");
        assertTrue(info.get().has("version"), "Info should contain version");
    }

    @Test
    void testGetInfoWithServerUnavailable() throws IOException {
        mockWebServer.shutdown();
        RspamdClient unavailableClient = new RspamdClient("non-existent-host", 9999);
        var info = unavailableClient.getInfo();
        assertFalse(info.isPresent(), "Info should not be available with invalid host/port");
    }

    @Test
    void testScanCleanFile() throws IOException {
        String cleanResponse = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(cleanResponse)
                .addHeader("Content-Type", "application/json"));

        Map<String, Object> result = client.scanFile(cleanFile);
        assertNotNull(result, "Scan result should not be null");
        assertFalse(result.isEmpty(), "Scan result should contain data");
        assertFalse((Boolean) result.get("spam"), "Clean file should not be marked as spam");
    }

    @Test
    void testScanSpamFile() throws IOException {
        String spamResponse = createScanResponse(true, 15.5, "SPAM_SIGNAL");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(spamResponse)
                .addHeader("Content-Type", "application/json"));

        Map<String, Object> result = client.scanFile(spamFile);
        assertNotNull(result, "Scan result should not be null");
        assertFalse(result.isEmpty(), "Scan result should contain data");
        assertTrue((Boolean) result.get("spam"), "Spam file should be marked as spam");
    }

    @Test
    void testScanCleanBytes() {
        String cleanResponse = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(cleanResponse)
                .addHeader("Content-Type", "application/json"));

        Map<String, Object> result = client.scanBytes(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertNotNull(result, "Scan result should not be null");
        assertFalse((Boolean) result.get("spam"), "Clean content should not be marked as spam");
    }

    @Test
    void testScanSpamBytes() {
        String spamResponse = createScanResponse(true, 15.5, "SPAM_SIGNAL");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(spamResponse)
                .addHeader("Content-Type", "application/json"));

        Map<String, Object> result = client.scanBytes(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertNotNull(result, "Scan result should not be null");
        assertTrue((Boolean) result.get("spam"), "Spam content should be marked as spam");
    }

    @Test
    void testScanCleanStream() {
        String cleanResponse = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(cleanResponse)
                .addHeader("Content-Type", "application/json"));

        InputStream inputStream = new ByteArrayInputStream(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = client.scanStream(inputStream);
        assertNotNull(result, "Scan result should not be null");
        assertFalse((Boolean) result.get("spam"), "Clean stream should not be marked as spam");
    }

    @Test
    void testScanSpamStream() {
        String spamResponse = createScanResponse(true, 15.5, "SPAM_SIGNAL");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(spamResponse)
                .addHeader("Content-Type", "application/json"));

        InputStream inputStream = new ByteArrayInputStream(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = client.scanStream(inputStream);
        assertNotNull(result, "Scan result should not be null");
        assertTrue((Boolean) result.get("spam"), "Spam stream should be marked as spam");
    }

    @Test
    void testIsSpamWithCleanContent() {
        String cleanResponse = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(cleanResponse)
                .addHeader("Content-Type", "application/json"));

        boolean isSpam = client.isSpam(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertFalse(isSpam, "Clean content should not be detected as spam");
    }

    @Test
    void testIsSpamWithSpamContent() {
        String spamResponse = createScanResponse(true, 15.5, "SPAM_SIGNAL");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(spamResponse)
                .addHeader("Content-Type", "application/json"));

        boolean isSpam = client.isSpam(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertTrue(isSpam, "Spam content should be detected as spam");
    }

    @Test
    void testIsSpamWithFile() throws IOException {
        String cleanResponse = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(cleanResponse)
                .addHeader("Content-Type", "application/json"));

        boolean isSpam = client.isSpam(cleanFile);
        assertFalse(isSpam, "Clean file should not be detected as spam");
    }

    @Test
    void testGetScore() {
        String response = createScanResponse(true, 25.5, "SPAM_SIGNAL");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(response)
                .addHeader("Content-Type", "application/json"));

        client.scanBytes(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        double score = client.getScore();
        assertEquals(25.5, score, "Score should match the scanned result");
    }

    @Test
    void testGetSymbols() {
        String response = createScanResponse(true, 15.5, "SPAM_SIGNAL", "BAYES_SPAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(response)
                .addHeader("Content-Type", "application/json"));

        client.scanBytes(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> symbols = client.getSymbols();
        assertFalse(symbols.isEmpty(), "Symbols should not be empty");
        assertTrue(symbols.containsKey("SPAM_SIGNAL"), "Symbols should contain SPAM_SIGNAL");
    }

    @Test
    void testGetLastScanResult() {
        String response = createScanResponse(false, 1.5, "BAYES_HAM");
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(response)
                .addHeader("Content-Type", "application/json"));

        client.scanBytes(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> lastResult = client.getLastScanResult();
        assertNotNull(lastResult, "Last scan result should not be null");
        assertFalse(lastResult.isEmpty(), "Last scan result should contain data");
    }

    @Test
    void testScanResponseError() {
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 500 Internal Server Error"));

        Map<String, Object> result = client.scanBytes(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isEmpty(), "Failed scan should return empty result");
    }

    @Test
    void testScanNonExistentFile() {
        File nonExistentFile = new File("non-existent-file.txt");
        assertThrows(IOException.class, () -> client.scanFile(nonExistentFile));
    }

    @Test
    void testMultipleScanRequests() {
        // Enqueue multiple responses
        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(createScanResponse(false, 1.5, "BAYES_HAM"))
                .addHeader("Content-Type", "application/json"));

        mockWebServer.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(createScanResponse(true, 15.5, "SPAM_SIGNAL"))
                .addHeader("Content-Type", "application/json"));

        // First scan
        Map<String, Object> result1 = client.scanBytes(HAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertFalse((Boolean) result1.get("spam"), "First scan should be clean");

        // Second scan
        Map<String, Object> result2 = client.scanBytes(SPAM_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertTrue((Boolean) result2.get("spam"), "Second scan should be spam");
    }

    /**
     * Helper method to create a mock Rspamd scan response.
     *
     * @param isSpam Whether the content is spam
     * @param score  The spam score
     * @param symbols The detected symbols (rules)
     * @return JSON string representing the scan response
     */
    private String createScanResponse(boolean isSpam, double score, String... symbols) {
        JsonObject response = new JsonObject();
        response.addProperty("spam", isSpam);
        response.addProperty("score", score);
        response.addProperty("required_score", 7.0);

        JsonObject symbolsObj = new JsonObject();
        for (String symbol : symbols) {
            symbolsObj.addProperty(symbol, 5.0);
        }
        response.add("symbols", symbolsObj);

        return response.toString();
    }
}
