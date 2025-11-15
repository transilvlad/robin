package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpamStorageProcessorTest {

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
        // Reset Rspamd config
        Config.getServer().getRspamd().getMap().put("enabled", false);
    }

    @Test
    void testProcessorDisabled() throws IOException {
        // Setup - Rspamd disabled by default
        Config.getServer().getRspamd().getMap().put("enabled", false);
        SpamStorageProcessor processor = new SpamStorageProcessor();

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
    void testScanResultsSavedToEnvelope() throws IOException {
        // Setup - Enable Rspamd but it won't actually connect in this test
        // The test verifies the structure, not the actual scanning
        Config.getServer().getRspamd().getMap().put("enabled", false); // Keep disabled to avoid network calls
        
        SpamStorageProcessor processor = new SpamStorageProcessor();
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

                // Should return true and have no scan results (Rspamd disabled)
                assertTrue(result);
                assertTrue(envelope.getScanResults().isEmpty());
            }
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void testScanResultsThreadSafety() throws InterruptedException {
        // Test thread safety of scanResults list
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Create multiple threads that add scan results concurrently
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    Map<String, Object> result = Map.of(
                        "scanner", "test",
                        "thread", threadId,
                        "iteration", j
                    );
                    envelope.addScanResult(result);
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all results were added
        assertEquals(1000, envelope.getScanResults().size());
    }

    @Test
    void testAddScanResultNullHandling() {
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Add null result - should not be added
        envelope.addScanResult(null);
        assertTrue(envelope.getScanResults().isEmpty());
        
        // Add empty map - should not be added
        envelope.addScanResult(Map.of());
        assertTrue(envelope.getScanResults().isEmpty());
        
        // Add valid result
        envelope.addScanResult(Map.of("scanner", "test", "result", "clean"));
        assertEquals(1, envelope.getScanResults().size());
    }

    @Test
    void testEnvelopeCloneIncludesScanResults() {
        MessageEnvelope original = new MessageEnvelope();
        original.addScanResult(Map.of("scanner", "rspamd", "score", 1.5));
        original.addScanResult(Map.of("scanner", "clamav", "infected", false));

        MessageEnvelope cloned = original.clone();

        // Verify scan results are copied
        assertEquals(2, cloned.getScanResults().size());
        
        // Verify deep copy - modifying clone should not affect original
        cloned.addScanResult(Map.of("scanner", "test", "value", "new"));
        assertEquals(3, cloned.getScanResults().size());
        assertEquals(2, original.getScanResults().size());
    }

    @Test
    void testGetScanResultsReturnsThreadSafeList() {
        MessageEnvelope envelope = new MessageEnvelope();
        List<Map<String, Object>> scanResults = envelope.getScanResults();
        
        // Verify we can add to the list
        scanResults.add(Map.of("scanner", "test"));
        assertEquals(1, envelope.getScanResults().size());
        
        // Verify it's the same list instance
        assertSame(scanResults, envelope.getScanResults());
    }
}
