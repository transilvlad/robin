package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.transaction.SessionTransactionList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RelayDequeue class.
 * <p>Tests the dequeuing and processing logic for relay sessions.
 * <p>Uses in-memory queue database from test configuration.
 */
public class RelayDequeueTest {

    @TempDir
    Path tempDir;

    private PersistentQueue<RelaySession> testQueue;
    private RelayDequeue relayDequeue;

    @BeforeEach
    void setUp() {
        // Uses in-memory database from test config (all backends disabled)
        testQueue = PersistentQueue.getInstance();
        relayDequeue = new RelayDequeue(testQueue);
    }

    @AfterEach
    void tearDown() {
        // Cleanup the queue
        if (testQueue != null) {
            testQueue.close();
        }
    }

    @Test
    void testProcessBatchWithEmptyQueue() {
        // Given: Empty queue.
        assertEquals(0, testQueue.size());

        // When: Process batch.
        relayDequeue.processBatch(10, Instant.now().getEpochSecond());

        // Then: Queue should remain empty.
        assertEquals(0, testQueue.size());
    }

    @Test
    void testProcessBatchRespectsMaxDequeueLimit() {
        // Given: Queue with many items.
        for (int i = 0; i < 100; i++) {
            Session session = new Session();
            session.setUID("test-uid-" + i);
            testQueue.enqueue(new RelaySession(session));
        }

        // When: Process batch with limit of 5.
        int initialSize = (int) testQueue.size();
        relayDequeue.processBatch(5, Instant.now().getEpochSecond());

        // Then: Should process at most 5 items (some may be re-queued if relay fails).
        assertTrue(testQueue.size() >= initialSize - 5, "Should respect max dequeue limit");
    }

    @Test
    void testIsReadyForRetryNotReady() {
        // Given: Session created recently with short backoff.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        // bumpRetryCount sets lastRetryTime to now.
        relaySession.bumpRetryCount();

        long currentTime = Instant.now().getEpochSecond() + 30; // Only 30 seconds later.

        // When: Check if ready for retry (first retry needs 60 seconds).
        boolean ready = relayDequeue.isReadyForRetry(relaySession, currentTime);

        // Then: Should not be ready.
        assertFalse(ready, "Session should not be ready for retry yet");
    }

    @Test
    void testIsReadyForRetryReady() {
        // Given: Session with old lastRetryTime.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        // Bump once to set lastRetryTime, but we'll test with current time far in the future.
        relaySession.bumpRetryCount();

        long currentTime = Instant.now().getEpochSecond() + 120;

        // When: Check if ready for retry.
        boolean ready = relayDequeue.isReadyForRetry(relaySession, currentTime);

        // Then: Should be ready.
        assertTrue(ready, "Session should be ready for retry after sufficient time");
    }

    @Test
    void testCountRecipientsSingleEnvelope() {
        // Given: Session with one envelope and multiple recipients.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com", "pepper@example.com", "happy@example.com"));
        session.addEnvelope(envelope);

        // When: Count recipients.
        int count = relayDequeue.countRecipients(relaySession);

        // Then: Should return 3.
        assertEquals(3, count, "Should count all recipients");
    }

    @Test
    void testCountRecipientsMultipleEnvelopes() {
        // Given: Session with multiple envelopes.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setRcpts(List.of("tony@example.com", "pepper@example.com"));
        session.addEnvelope(envelope1);

        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setRcpts(List.of("happy@example.com"));
        session.addEnvelope(envelope2);

        // When: Count recipients.
        int count = relayDequeue.countRecipients(relaySession);

        // Then: Should return 3.
        assertEquals(3, count, "Should count all recipients across envelopes");
    }

    @Test
    void testCountRecipientsEmptyEnvelopes() {
        // Given: Session with no envelopes.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        // When: Count recipients.
        int count = relayDequeue.countRecipients(relaySession);

        // Then: Should return 0.
        assertEquals(0, count, "Should return 0 for empty envelopes");
    }

    @Test
    void testCountRecipientsNullRecipients() {
        // Given: Session with envelope but null recipients.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(null);
        session.addEnvelope(envelope);

        // When: Count recipients.
        int count = relayDequeue.countRecipients(relaySession);

        // Then: Should return 0 without throwing exception.
        assertEquals(0, count, "Should handle null recipients gracefully");
    }

    @Test
    void testProcessDeliveryResultsAllSuccessful() {
        // Given: Session with all successful deliveries.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com"));
        session.addEnvelope(envelope);

        // Simulate successful transaction (no errors).
        SessionTransactionList txList = session.getSessionTransactionList();
        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        // Empty errors list means success.
        txList.getEnvelopes().add(envTxList);

        // When: Process delivery results.
        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        // Then: Should have 1 removed, 0 remaining.
        assertEquals(1, result.getRemovedCount(), "Should remove successful envelope");
        assertEquals(0, result.getRemainingCount(), "Should have no remaining envelopes");
    }

    @Test
    void testProcessDeliveryResultsAllFailed() {
        // Given: Session with failed delivery.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        List<String> recipients = new ArrayList<>(List.of("tony@example.com", "pepper@example.com"));
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(recipients);
        session.addEnvelope(envelope);

        // Simulate failed transaction.
        SessionTransactionList txList = session.getSessionTransactionList();
        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
        envTxList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "550 Mailbox unavailable", true);
        txList.getEnvelopes().add(envTxList);

        // When: Process delivery results.
        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        // Then: Should have 0 removed, 1 remaining.
        assertEquals(0, result.getRemovedCount(), "Should not remove failed envelope");
        assertEquals(1, result.getRemainingCount(), "Should have 1 remaining envelope");
    }

    @Test
    void testProcessDeliveryResultsPartialSuccess() {
        // Given: Session with partial success (one recipient succeeded, one failed).
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        List<String> allRecipients = new ArrayList<>(List.of("tony@example.com", "pepper@example.com"));
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(allRecipients);
        session.addEnvelope(envelope);

        // Simulate partial failure.
        SessionTransactionList txList = session.getSessionTransactionList();
        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
        envTxList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "250 OK");
        txList.getEnvelopes().add(envTxList);

        // When: Process delivery results.
        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        // Then: Should have 0 removed, 1 remaining with only failed recipient.
        assertEquals(0, result.getRemovedCount(), "Should not remove partially failed envelope");
        assertEquals(1, result.getRemainingCount(), "Should have 1 remaining envelope");
        assertEquals(1, envelope.getRcpts().size(), "Should have only failed recipient");
        assertTrue(envelope.getRcpts().contains("tony@example.com"), "Should contain failed recipient");
    }

    @Test
    void testCleanupSuccessfulEnvelopesDeletesFiles() throws Exception {
        // Given: Envelope with an existing file.
        File tempFile = tempDir.resolve("test-envelope.eml").toFile();
        Files.writeString(tempFile.toPath(), "test content");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile(tempFile.getAbsolutePath());

        List<MessageEnvelope> envelopes = List.of(envelope);

        // When: Cleanup successful envelopes.
        relayDequeue.cleanupSuccessfulEnvelopes(envelopes);

        // Then: File should be deleted.
        assertFalse(Files.exists(tempFile.toPath()), "File should be deleted");
    }

    @Test
    void testCleanupSuccessfulEnvelopesHandlesNonExistentFiles() {
        // Given: Envelope with non-existent file.
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile("/nonexistent/file.eml");

        List<MessageEnvelope> envelopes = List.of(envelope);

        // When/Then: Should not throw exception.
        assertDoesNotThrow(() -> relayDequeue.cleanupSuccessfulEnvelopes(envelopes));
    }

    @Test
    void testCleanupSuccessfulEnvelopesHandlesNullFile() {
        // Given: Envelope with null file.
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile(null);

        List<MessageEnvelope> envelopes = List.of(envelope);

        // When/Then: Should not throw exception.
        assertDoesNotThrow(() -> relayDequeue.cleanupSuccessfulEnvelopes(envelopes));
    }

    @Test
    void testHandleRemainingEnvelopesFullyDelivered() {
        // Given: Session with no remaining envelopes.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        RelayDeliveryResult result = new RelayDeliveryResult(1, 0, new ArrayList<>());
        long initialQueueSize = testQueue.size();

        // When: Handle remaining envelopes.
        relayDequeue.handleRemainingEnvelopes(relaySession, result);

        // Then: Should not re-enqueue.
        assertEquals(initialQueueSize, testQueue.size(), "Should not re-enqueue when fully delivered");
    }

    @Test
    void testHandleRemainingEnvelopesRetryUnderLimit() {
        // Given: Session with remaining envelopes under retry limit.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        // Bump retry count to 5.
        for (int i = 0; i < 5; i++) {
            relaySession.bumpRetryCount();
        }
        assertEquals(5, relaySession.getRetryCount());

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com"));
        session.addEnvelope(envelope);

        RelayDeliveryResult result = new RelayDeliveryResult(0, 1, new ArrayList<>());

        // When: Handle remaining envelopes.
        relayDequeue.handleRemainingEnvelopes(relaySession, result);

        // Then: Should increment retry count and re-enqueue.
        assertEquals(6, relaySession.getRetryCount(), "Should increment retry count");
        assertEquals(1, testQueue.size(), "Should re-enqueue session");
    }

    @Test
    void testHandleRemainingEnvelopesMaxRetriesReached() {
        // Given: Session with remaining envelopes at max retry limit.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        // Bump retry count to 30 (max retries).
        for (int i = 0; i < 30; i++) {
            relaySession.bumpRetryCount();
        }
        assertEquals(30, relaySession.getRetryCount());

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com"));
        session.addEnvelope(envelope);

        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
        session.getSessionTransactionList().addEnvelope(envTxList);

        RelayDeliveryResult result = new RelayDeliveryResult(0, 1, new ArrayList<>());

        // When: Handle remaining envelopes.
        relayDequeue.handleRemainingEnvelopes(relaySession, result);

        // Then: Should generate bounces (enqueue bounce messages).
        assertFalse(testQueue.isEmpty(), "Should enqueue at least one bounce message");
    }

    @Test
    void testRetrySessionIncrementsRetryCount() {
        // Given: Session with retry count 5.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        for (int i = 0; i < 5; i++) {
            relaySession.bumpRetryCount();
        }
        assertEquals(5, relaySession.getRetryCount());

        MessageEnvelope envelope = new MessageEnvelope();
        session.addEnvelope(envelope);

        // When: Retry session.
        relayDequeue.retrySession(relaySession);

        // Then: Should increment to 6 and enqueue.
        assertEquals(6, relaySession.getRetryCount(), "Should increment retry count");
        assertEquals(1, testQueue.size(), "Should enqueue session");
    }

    @Test
    void testReEnqueueSessionAddsToQueue() {
        // Given: Session to re-enqueue.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        assertEquals(0, testQueue.size());

        // When: Re-enqueue session.
        relayDequeue.reEnqueueSession(relaySession, "Just because");

        // Then: Should be in queue.
        assertEquals(1, testQueue.size(), "Should add session to queue");
    }

    @Test
    void testDeliveryResultGettersWork() {
        // Given: RelayDeliveryResult with specific values.
        List<MessageEnvelope> envelopes = new ArrayList<>();
        RelayDeliveryResult result = new RelayDeliveryResult(3, 2, envelopes);

        // When/Then: Getters should return correct values.
        assertEquals(3, result.getRemovedCount());
        assertEquals(2, result.getRemainingCount());
        assertSame(envelopes, result.getSuccessfulEnvelopes());
    }

    @Test
    void testProcessBatchHandlesNullDequeue() {
        // Given: Empty queue.
        assertEquals(0, testQueue.size());

        // When: Process batch.
        relayDequeue.processBatch(10, Instant.now().getEpochSecond());

        // Then: Should handle gracefully without errors.
        assertEquals(0, testQueue.size());
    }

    @Test
    void testProcessSessionReEnqueuesWhenNotReady() {
        // Given: Session not ready for retry.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        relaySession.bumpRetryCount(); // Sets lastRetryTime to now.

        long currentTime = Instant.now().getEpochSecond() + 10; // Too soon (needs 60 seconds).

        // When: Process session.
        relayDequeue.processSession(relaySession, currentTime);

        // Then: Should re-enqueue without attempting delivery.
        assertEquals(1, testQueue.size(), "Should re-enqueue session");
    }

    @Test
    void testProcessSessionReadyForRetryWithNoEnvelopes() {
        // Given: Session ready for retry but with no envelopes (edge case).
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        long currentTime = Instant.now().getEpochSecond() + 120; // Well past retry time.

        // When: Process session.
        relayDequeue.processSession(relaySession, currentTime);

        // Then: Should complete without errors and not re-enqueue.
        assertEquals(0, testQueue.size(), "Should not re-enqueue empty session");
    }

    @Test
    void testBumpRetryCountUpdatesLastRetryTime() {
        // Given: New relay session.
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        long initialRetryTime = relaySession.getLastRetryTime();

        // When: Bump retry count.
        relaySession.bumpRetryCount();

        // Then: Last retry time should be updated.
        assertTrue(relaySession.getLastRetryTime() >= initialRetryTime, "Last retry time should be updated");
        assertEquals(1, relaySession.getRetryCount(), "Retry count should be 1");
    }

    // Helper method to create a test session.
    private Session createTestSession() {
        Session session = new Session();
        session.setUID("test-uid-" + System.currentTimeMillis());
        return session;
    }
}
