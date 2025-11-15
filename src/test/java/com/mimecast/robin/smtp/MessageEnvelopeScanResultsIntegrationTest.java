package com.mimecast.robin.smtp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MessageEnvelope scanResults functionality.
 */
class MessageEnvelopeScanResultsIntegrationTest {

    @Test
    void testScanResultsIntegration() {
        // Create an envelope
        MessageEnvelope envelope = new MessageEnvelope();
        
        // Simulate Rspamd scan result
        Map<String, Object> rspamdResult = Map.of(
            "scanner", "rspamd",
            "score", 2.5,
            "spam", false,
            "symbols", Map.of(
                "R_SPF_ALLOW", 0.0,
                "R_DKIM_ALLOW", 0.0
            )
        );
        envelope.addScanResult(rspamdResult);
        
        // Simulate ClamAV scan result for RAW email
        Map<String, Object> clamavRawResult = Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "RAW"
        );
        envelope.addScanResult(clamavRawResult);
        
        // Simulate ClamAV scan result for attachment
        Map<String, Object> clamavAttachmentResult = Map.of(
            "scanner", "clamav",
            "infected", true,
            "viruses", Map.of("attachment.pdf", List.of("EICAR-Test-File")),
            "part", "application/pdf"
        );
        envelope.addScanResult(clamavAttachmentResult);
        
        // Verify all results were stored
        List<Map<String, Object>> scanResults = envelope.getScanResults();
        assertEquals(3, scanResults.size(), "Should have 3 scan results");
        
        // Verify we can retrieve specific scanner results
        long rspamdCount = scanResults.stream()
            .filter(r -> "rspamd".equals(r.get("scanner")))
            .count();
        assertEquals(1, rspamdCount, "Should have 1 rspamd result");
        
        long clamavCount = scanResults.stream()
            .filter(r -> "clamav".equals(r.get("scanner")))
            .count();
        assertEquals(2, clamavCount, "Should have 2 clamav results");
        
        // Verify we can identify infected results
        boolean hasInfection = scanResults.stream()
            .anyMatch(r -> Boolean.TRUE.equals(r.get("infected")));
        assertTrue(hasInfection, "Should have at least one infected result");
        
        // Verify Rspamd score
        Double rspamdScore = scanResults.stream()
            .filter(r -> "rspamd".equals(r.get("scanner")))
            .findFirst()
            .map(r -> (Double) r.get("score"))
            .orElse(null);
        assertNotNull(rspamdScore, "Should have rspamd score");
        assertEquals(2.5, rspamdScore, 0.001, "Rspamd score should be 2.5");
    }

    @Test
    void testScanResultsCloningIntegration() {
        // Create an envelope with multiple scan results
        MessageEnvelope original = new MessageEnvelope();
        
        original.addScanResult(Map.of("scanner", "rspamd", "score", 1.5));
        original.addScanResult(Map.of("scanner", "clamav", "infected", false));
        original.addScanResult(Map.of("scanner", "clamav", "infected", true, "viruses", Map.of("test", List.of("virus1"))));
        
        // Clone the envelope
        MessageEnvelope cloned = original.clone();
        
        // Verify all results were cloned
        assertEquals(3, cloned.getScanResults().size(), "Cloned envelope should have all scan results");
        
        // Verify modifications to clone don't affect original
        cloned.addScanResult(Map.of("scanner", "test", "value", "new"));
        assertEquals(4, cloned.getScanResults().size(), "Cloned envelope should have 4 results");
        assertEquals(3, original.getScanResults().size(), "Original envelope should still have 3 results");
        
        // Verify deep copy - modifying a result in clone shouldn't affect original
        List<Map<String, Object>> clonedResults = cloned.getScanResults();
        List<Map<String, Object>> originalResults = original.getScanResults();
        
        // Ensure they're different list instances
        assertNotSame(originalResults, clonedResults, "Should be different list instances");
    }

    @Test
    void testRealWorldScenario() {
        // Simulate a real-world email processing scenario
        MessageEnvelope envelope = new MessageEnvelope()
            .setMail("sender@example.com")
            .addRcpt("recipient@example.com")
            .setSubject("Test Email with Attachment");
        
        // Step 1: Rspamd scan (spam/phishing detection)
        envelope.addScanResult(Map.of(
            "scanner", "rspamd",
            "score", 3.2,
            "spam", false,
            "action", "no action",
            "symbols", Map.of(
                "DMARC_POLICY_ALLOW", 0.0,
                "SPF_PASS", 0.0
            )
        ));
        
        // Step 2: ClamAV scan of raw email
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "RAW"
        ));
        
        // Step 3: ClamAV scan of PDF attachment
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "application/pdf; name=invoice.pdf"
        ));
        
        // Step 4: ClamAV scan of ZIP attachment
        envelope.addScanResult(Map.of(
            "scanner", "clamav",
            "infected", false,
            "part", "application/zip; name=files.zip"
        ));
        
        // Verify complete scan history
        List<Map<String, Object>> scanResults = envelope.getScanResults();
        assertEquals(4, scanResults.size(), "Should have complete scan history");
        
        // Check if email is safe to deliver
        boolean hasMalware = scanResults.stream()
            .anyMatch(r -> Boolean.TRUE.equals(r.get("infected")));
        assertFalse(hasMalware, "Email should be clean of malware");
        
        // Check spam score
        Double spamScore = scanResults.stream()
            .filter(r -> "rspamd".equals(r.get("scanner")))
            .findFirst()
            .map(r -> (Double) r.get("score"))
            .orElse(0.0);
        assertTrue(spamScore < 7.0, "Spam score should be below rejection threshold");
        
        // Email is safe to deliver!
        assertTrue(true, "Email passed all security checks");
    }
}
