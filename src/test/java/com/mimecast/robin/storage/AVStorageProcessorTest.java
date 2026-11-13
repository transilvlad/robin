package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.scanners.ClamAVMockServer;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class AVStorageProcessorTest {

    private static final String TEST_EMAIL_CONTENT = "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Test Email\r\n" +
            "\r\n" +
            "This is a test email.\r\n";

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @AfterEach
    void cleanup() {
        // Reset ClamAV config
        Config.getServer().getClamAV().getMap().put("enabled", false);
    }

    @Test
    void testProcessorDisabled() throws IOException {
        // Setup - ClamAV disabled by default
        Config.getServer().getClamAV().getMap().put("enabled", false);
        AVStorageProcessor processor = new AVStorageProcessor();

        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("recipient@example.com");
        connection.getSession().addEnvelope(envelope);

        // Create test file
        Path tmpFile = Files.createTempFile("test-email-", ".eml");
        try {
            Files.writeString(tmpFile, TEST_EMAIL_CONTENT);
            envelope.setFile(tmpFile.toString());

            // Process
            try (EmailParser parser = new EmailParser(tmpFile.toString()).parse()) {
                boolean result = processor.process(connection, parser);

                // Should return true (success) and no scan results added
                assertTrue(result);
                assertTrue(envelope.getScanResults().isEmpty());
            }
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void scansRecoveredEpilogueOnlyWhenAttachmentScanningIsEnabled() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ClamAVMockServer server = new ClamAVMockServer(port);
        assertTrue(server.start());
        Map<String, Object> config = Config.getServer().getClamAV().getMap();
        Map<String, Object> original = new HashMap<>(config);
        try {
            config.put("enabled", true);
            config.put("host", "localhost");
            config.put("port", port);
            String fixture = Files.readString(Path.of("src/test/resources/cases/sources/eicar-epilogue.eml"));
            String encodedEicar = "WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLV\r\n"
                    + "NUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo=\r\n";
            assertTrue(fixture.contains(encodedEicar));
            byte[] raw = fixture.replace(encodedEicar, "U0lNVUxBVEVEX1ZJUlVT\r\n")
                    .getBytes(StandardCharsets.US_ASCII);

            for (String scenario : List.of("raw-only", "discard", "reject")) {
                boolean scanAttachments = !scenario.equals("raw-only");
                config.put("scanAttachments", scanAttachments);
                config.put("onVirus", scenario.equals("reject") ? "reject" : "discard");
                MessageEnvelope envelope = new MessageEnvelope().setBytes(raw);
                ConnectionMock connection = new ConnectionMock(new StringBuilder());
                connection.getSession().addEnvelope(envelope);
                try (EmailParser parser = new EmailParser(envelope.openMessageStream()).parse()) {
                    assertEquals(!scenario.equals("reject"), new AVStorageProcessor().process(connection, parser));
                }
                assertEquals(scenario.equals("discard"), envelope.isBlackholed());
                assertEquals(scenario.equals("reject"), connection.getOutput().contains("554 5.7.1 Virus detected"));
                assertEquals(scanAttachments ? 2 : 1, envelope.getScanResults().size());
                if (scanAttachments) {
                    Map<String, Object> result = envelope.getScanResults().getLast();
                    assertEquals(true, result.get("infected"));
                    assertTrue(((String) result.get("part")).startsWith("unframed "));
                }
            }
        } finally {
            config.clear();
            config.putAll(original);
            server.stop();
        }
    }

    @Test
    void testScanResultsStructure() {
        // Test that scan results have the expected structure
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Simulate adding a ClamAV result manually
        Map<String, Object> clamavResult = Map.of(
            "scanner", "clamav",
            "infected", true,
            "viruses", Map.of("stream", List.of("EICAR-Test-File")),
            "part", "RAW"
        );
        envelope.addScanResult(clamavResult);
        
        List<Map<String, Object>> results = envelope.getScanResults();
        assertEquals(1, results.size());
        
        Map<String, Object> result = results.get(0);
        assertEquals("clamav", result.get("scanner"));
        assertEquals(true, result.get("infected"));
        assertTrue(result.containsKey("viruses"));
        assertEquals("RAW", result.get("part"));
    }

    @Test
    void testMultipleScanResults() {
        // Test that both Rspamd and ClamAV results can be stored
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Add Rspamd result
        envelope.addScanResult(Map.of(
            "scanner", "rspamd",
            "score", 1.5,
            "symbols", Map.of()
        ));
        
        // Add ClamAV result
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "RAW"
        ));
        
        List<Map<String, Object>> results = envelope.getScanResults();
        assertEquals(2, results.size());
        
        // Verify we can distinguish between scanners
        long rspamdCount = results.stream()
            .filter(r -> "rspamd".equals(r.get("scanner")))
            .count();
        long clamavCount = results.stream()
            .filter(r -> "clamav".equals(r.get("scanner")))
            .count();
        
        assertEquals(1, rspamdCount);
        assertEquals(1, clamavCount);
    }

    @Test
    void testScanResultsAggregation() {
        // Test that multiple ClamAV scans (RAW + attachments) can be aggregated
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Add RAW scan result
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "RAW"
        ));
        
        // Add attachment scan results
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "application/pdf"
        ));
        
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", true,
            "viruses", Map.of("attachment", List.of("Test-Virus")),
            "part", "application/zip"
        ));
        
        List<Map<String, Object>> results = envelope.getScanResults();
        assertEquals(3, results.size());
        
        // Count infected vs clean scans
        long infectedCount = results.stream()
            .filter(r -> "clamav".equals(r.get("scanner")))
            .filter(r -> Boolean.TRUE.equals(r.get("infected")))
            .count();
        
        assertEquals(1, infectedCount);
    }
}
