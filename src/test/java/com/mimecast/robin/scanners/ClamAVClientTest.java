package com.mimecast.robin.scanners;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClamAVClient.
 * <p>
 * These tests use a mock ClamAV server implementation to avoid requiring a real ClamAV installation.
 * The mock server simulates ClamAV protocol responses for testing client functionality.
 */
class ClamAVClientTest {

    private static final String EICAR_TEST_SIGNATURE = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    private static final String CUSTOM_HOST = "localhost";

    private ClamAVMockServer mockServer;
    private ClamAVClient client;
    private File cleanFile;
    private File virusFile;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Allocate an ephemeral free port.
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            serverPort = ss.getLocalPort();
        }

        // Start the mock ClamAV server on the specified port.
        mockServer = new ClamAVMockServer(serverPort);
        assertTrue(mockServer.start(), "Mock ClamAV server should start successfully");

        // Brief pause to ensure accept loop started.
        Thread.sleep(100);

        // Initialize client with default settings, pointing to our mock server.
        client = new ClamAVClient(CUSTOM_HOST, serverPort);

        // Create a test clean file.
        cleanFile = File.createTempFile("clamav-test-clean-", ".txt");
        Files.writeString(cleanFile.toPath(), "This is a clean test file.");

        // Create a test virus file using simulated marker to avoid OS AV.
        virusFile = File.createTempFile("clamav-test-virus-", ".txt");
        Files.writeString(virusFile.toPath(), "SIMULATED_VIRUS test payload");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Stop the mock server.
        if (mockServer != null) mockServer.stop();

        // Clean up test files.
        if (cleanFile != null) Files.deleteIfExists(cleanFile.toPath());
        if (virusFile != null) Files.deleteIfExists(virusFile.toPath());
    }

    @Test
    void testConstructorWithDefaultValues() {
        ClamAVClient defaultClient = new ClamAVClient();
        assertNotNull(defaultClient);
    }

    @Test
    void testConstructorWithCustomValues() {
        ClamAVClient customClient = new ClamAVClient(CUSTOM_HOST, serverPort);
        assertNotNull(customClient);
    }

    @Test
    void testPingWithServerAvailable() {
        boolean result = client.ping();
        assertTrue(result, "ClamAV server should be available");
    }

    @Test
    void testPingWithServerUnavailable() {
        // Create client with invalid host/port to ensure it's unavailable.
        ClamAVClient unavailableClient = new ClamAVClient("non-existent-host", 9999);
        boolean result = unavailableClient.ping();
        assertFalse(result, "ClamAV server should not be available with invalid host/port");
    }

    @Test
    void testGetVersionWithServerAvailable() {
        Optional<String> version = client.getVersion();
        assertTrue(version.isPresent(), "Version should be available when server is running");
        assertFalse(version.get().isEmpty(), "Version should not be empty");
    }

    @Test
    void testGetVersionWithServerUnavailable() {
        // Create client with invalid host/port to ensure it's unavailable.
        ClamAVClient unavailableClient = new ClamAVClient("non-existent-host", 9999);
        Optional<String> version = unavailableClient.getVersion();
        assertFalse(version.isPresent(), "Version should not be available with invalid host/port");
    }

    @Test
    void testScanCleanFile() throws IOException {
        ScanResult result = client.scanFile(cleanFile);
        assertInstanceOf(ScanResult.OK.class, result, "Clean file should return OK result");
    }

    @Test
    void testScanVirusFile() throws IOException {
        ScanResult result = client.scanFile(virusFile);
        assertInstanceOf(ScanResult.VirusFound.class, result, "Virus file should return VirusFound result");
    }

    @Test
    void testScanCleanBytes() {
        String cleanContent = "This is clean content";
        ScanResult result = client.scanBytes(cleanContent.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(ScanResult.OK.class, result, "Clean stream should return OK result");
    }

    @Test
    void testScanVirusBytes() {
        ScanResult result = client.scanBytes(EICAR_TEST_SIGNATURE.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(ScanResult.VirusFound.class, result, "Virus stream should return VirusFound result");
    }

    @Test
    void testScanCleanStream() {
        String cleanContent = "This is clean content";
        InputStream inputStream = new ByteArrayInputStream(cleanContent.getBytes(StandardCharsets.UTF_8));
        ScanResult result = client.scanStream(inputStream);
        assertInstanceOf(ScanResult.OK.class, result, "Clean stream should return OK result");
    }

    @Test
    void testScanVirusStream() {
        InputStream inputStream = new ByteArrayInputStream(EICAR_TEST_SIGNATURE.getBytes(StandardCharsets.UTF_8));
        ScanResult result = client.scanStream(inputStream);
        assertInstanceOf(ScanResult.VirusFound.class, result, "Virus stream should return VirusFound result");
    }

    @Test
    void testIsInfectedWithCleanFile() throws IOException {
        boolean isInfected = client.isInfected(cleanFile);
        assertFalse(isInfected, "Clean file should not be reported as infected");
    }

    @Test
    void testIsInfectedWithVirusFile() throws IOException {
        boolean isInfected = client.isInfected(virusFile);
        assertTrue(isInfected, "Virus file should be reported as infected");
    }

    @Test
    void testGetVirusesAfterCleanScan() throws IOException {
        client.isInfected(cleanFile);
        Map<String, Collection<String>> viruses = client.getViruses();
        assertNull(viruses, "No viruses should be reported for clean file");
    }

    @Test
    void testGetVirusesAfterVirusScan() throws IOException {
        client.isInfected(virusFile);
        Map<String, Collection<String>> viruses = client.getViruses();
        assertNotNull(viruses, "Viruses should be reported for virus file");
        assertFalse(viruses.isEmpty(), "Virus map should not be empty");
    }

    @Test
    void cleanScanClearsPreviousViruses() throws IOException {
        assertTrue(client.isInfected(virusFile));
        assertFalse(client.isInfected(cleanFile));
        assertNull(client.getViruses());
    }

    @Test
    void unavailableScannerFailsRatherThanReportingClean() {
        mockServer.stop();
        assertThrows(UncheckedIOException.class, () -> client.isInfected(new byte[]{1}));
    }

    @Test
    void failedScanClearsPreviousViruses() throws IOException {
        assertTrue(client.isInfected(virusFile));
        mockServer.setScanResponse("stream: scan failed ERROR\0");
        assertThrows(UncheckedIOException.class, () -> client.isInfected(new byte[]{1}));
        assertNull(client.getViruses());
    }

    @Test
    void stalledResponseHasABoundedDeadline() throws Exception {
        try (var server = new java.net.ServerSocket(0);
             var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var accepted = worker.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new java.io.DataInputStream(socket.getInputStream());
                    while (input.readByte() != 0) { }
                    int count;
                    while ((count = input.readInt()) != 0) {
                        input.readNBytes(count);
                    }
                    waiting.await();
                }
                return null;
            });
            try {
                ClamAVClient bounded = new ClamAVClient("localhost", server.getLocalPort(), 1);
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                        assertThrows(UncheckedIOException.class, () -> bounded.scanBytes(new byte[]{1})));
            } finally {
                waiting.countDown();
                accepted.get(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void scannerErrorsAndTruncatedResponsesFailExplicitly() {
        for (String response : new String[]{"stream: size limit exceeded ERROR\0",
                "unknown\0", "stream: OK", "\0", "stream:  FOUND\0"}) {
            mockServer.setScanResponse(response);
            assertThrows(UncheckedIOException.class, () -> client.isInfected(new byte[]{1}));
            assertNull(client.getViruses());
        }
    }

    @Test
    void stalledUploadCannotHoldSmtpThreadIndefinitely() throws Exception {
        try (var server = new java.net.ServerSocket(0);
             var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var accepted = worker.submit(() -> {
                try (var socket = server.accept()) {
                    waiting.await();
                }
                return null;
            });
            try {
                ClamAVClient bounded = new ClamAVClient("localhost", server.getLocalPort(), 1);
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                        assertThrows(UncheckedIOException.class, () -> bounded.scanBytes(new byte[32 * 1024 * 1024])));
            } finally {
                waiting.countDown();
                accepted.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void testScanNonExistentFile() {
        File nonExistentFile = new File("non-existent-file.txt");
        assertThrows(IOException.class, () -> client.scanFile(nonExistentFile));
    }

    @Test
    void testGetVersionStripsExtendedMetadata() {
        mockServer.setVERSION_RESPONSE("ClamAV 0.103.9/25213/Wed Oct 23 10:00:00 2025/extra/info");
        Optional<String> version = client.getVersion();
        assertTrue(version.isPresent(), "Version should be present");
        assertEquals("ClamAV 0.103.9", version.get(), "Extended metadata after first slash should be stripped");
    }
}
