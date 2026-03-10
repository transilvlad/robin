package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.transaction.SessionTransactionList;
import com.mimecast.robin.storage.PooledLmtpDelivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RelayDequeue class.
 * <p>Uses the in-memory queue database from test configuration.
 */
@Isolated
class RelayDequeueTest {

    @TempDir
    Path tempDir;

    private PersistentQueue<RelaySession> testQueue;
    private RelayDequeue relayDequeue;

    @BeforeEach
    void setUp() {
        testQueue = PersistentQueue.getInstance();
        testQueue.clear();
        relayDequeue = new TestRelayDequeue(testQueue, null);
    }

    @AfterEach
    void tearDown() {
        if (testQueue != null) {
            testQueue.clear();
            testQueue.close();
        }
    }

    @Test
    void testProcessClaimedItemAcknowledgesMissingSession() {
        RelaySession relaySession = new RelaySession(null);
        testQueue.enqueue(relaySession);
        QueueItem<RelaySession> claimed = claimSingle(relaySession.getUID());

        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, Instant.now().getEpochSecond());

        assertNotNull(result);
        assertEquals(QueueMutationType.ACK, result.mutation().type());
        assertTrue(result.newItems().isEmpty());
        assertTrue(result.cleanupPaths().isEmpty());

        testQueue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));
        assertEquals(0, testQueue.size());
        assertNull(testQueue.getByUID(relaySession.getUID()));
    }

    @Test
    void testProcessClaimedItemAcknowledgesFullyDeliveredSession() {
        RelaySession relaySession = relaySessionWithEnvelope("success", List.of("tony@example.com"));
        relayDequeue = new TestRelayDequeue(testQueue, session -> session.getSession().getSessionTransactionList()
                .addEnvelope(new EnvelopeTransactionList()));
        testQueue.enqueue(relaySession);
        QueueItem<RelaySession> claimed = claimSingle(relaySession.getUID());

        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, Instant.now().getEpochSecond());

        assertEquals(QueueMutationType.ACK, result.mutation().type());
        assertTrue(result.newItems().isEmpty());
        testQueue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));
        relayDequeue.deleteEnvelopeFiles(result.cleanupPaths());

        assertEquals(0, testQueue.size());
        assertNull(testQueue.getByUID(relaySession.getUID()));
    }

    @Test
    void testProcessClaimedItemUsesPooledLmtpDeliveryForLmtpProtocol() {
        RelaySession relaySession = relaySessionWithEnvelope("lmtp-success", List.of("tony@example.com"));
        relaySession.setProtocol("lmtp");
        TestPooledLmtpDelivery pooledDelivery = new TestPooledLmtpDelivery();
        relayDequeue = new RelayDequeue(testQueue, pooledDelivery);
        testQueue.enqueue(relaySession);
        QueueItem<RelaySession> claimed = claimSingle(relaySession.getUID());

        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, Instant.now().getEpochSecond());

        assertEquals(1, pooledDelivery.invocations);
        assertEquals(QueueMutationType.ACK, result.mutation().type());
        testQueue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));
        assertEquals(0, testQueue.size());
        assertNull(testQueue.getByUID(relaySession.getUID()));
    }

    @Test
    void testProcessClaimedItemReschedulesRetryableFailure() {
        RelaySession relaySession = relaySessionWithEnvelope("retry", List.of("tony@example.com", "pepper@example.com"));
        relayDequeue = new TestRelayDequeue(testQueue, session -> {
            EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
            envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
            envTxList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "250 OK");
            session.getSession().getSessionTransactionList().addEnvelope(envTxList);
        });
        testQueue.enqueue(relaySession);
        long now = Instant.now().getEpochSecond();
        QueueItem<RelaySession> claimed = claimSingle(relaySession.getUID());

        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, now);

        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertTrue(result.newItems().isEmpty());
        testQueue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));
        relayDequeue.deleteEnvelopeFiles(result.cleanupPaths());

        QueueItem<RelaySession> updated = testQueue.getByUID(relaySession.getUID());
        assertNotNull(updated);
        assertEquals(1, testQueue.size());
        assertEquals(QueueItemState.READY, updated.getState());
        assertEquals(1, updated.getRetryCount());
        assertEquals(1, updated.getPayload().getRetryCount());
        assertTrue(updated.getNextAttemptAtEpochSeconds() > now);
        assertEquals(List.of("tony@example.com"), updated.getPayload().getSession().getEnvelopes().getFirst().getRcpts());
    }

    @Test
    void testProcessClaimedItemMarksDeadWhenRetryLimitIsReached() {
        RelaySession relaySession = relaySessionWithEnvelope("dead", List.of("tony@example.com"));
        for (int i = 0; i < relaySession.getMaxRetryCount(); i++) {
            relaySession.bumpRetryCount();
        }

        TestRelayDequeue testRelayDequeue = new TestRelayDequeue(testQueue, session -> {
            EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
            envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
            session.getSession().getSessionTransactionList().addEnvelope(envTxList);
        });
        relayDequeue = testRelayDequeue;

        testQueue.enqueue(relaySession);
        QueueItem<RelaySession> claimed = claimSingle(relaySession.getUID());

        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, Instant.now().getEpochSecond());

        assertEquals(QueueMutationType.DEAD, result.mutation().type());
        assertTrue(result.cleanupPaths().isEmpty());
        testQueue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));

        QueueItem<RelaySession> updated = testQueue.getByUID(relaySession.getUID());
        assertNotNull(updated);
        assertEquals(0, testQueue.size());
        assertEquals(QueueItemState.DEAD, updated.getState());
        assertEquals(1, testQueue.stats().deadCount());
    }

    @Test
    void testIsReadyForRetryNotReady() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        relaySession.bumpRetryCount();

        long currentTime = Instant.now().getEpochSecond() + 30;

        assertFalse(relayDequeue.isReadyForRetry(relaySession, currentTime));
    }

    @Test
    void testIsReadyForRetryReady() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);
        relaySession.bumpRetryCount();

        long currentTime = Instant.now().getEpochSecond() + 120;

        assertTrue(relayDequeue.isReadyForRetry(relaySession, currentTime));
    }

    @Test
    void testCountRecipientsSingleEnvelope() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com", "pepper@example.com", "happy@example.com"));
        session.addEnvelope(envelope);

        assertEquals(3, relayDequeue.countRecipients(relaySession));
    }

    @Test
    void testCountRecipientsMultipleEnvelopes() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setRcpts(List.of("tony@example.com", "pepper@example.com"));
        session.addEnvelope(envelope1);

        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setRcpts(List.of("happy@example.com"));
        session.addEnvelope(envelope2);

        assertEquals(3, relayDequeue.countRecipients(relaySession));
    }

    @Test
    void testCountRecipientsEmptyEnvelopes() {
        assertEquals(0, relayDequeue.countRecipients(new RelaySession(createTestSession())));
    }

    @Test
    void testCountRecipientsNullRecipients() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(null);
        session.addEnvelope(envelope);

        assertEquals(0, relayDequeue.countRecipients(relaySession));
    }

    @Test
    void testProcessDeliveryResultsAllSuccessful() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(List.of("tony@example.com"));
        session.addEnvelope(envelope);

        SessionTransactionList txList = session.getSessionTransactionList();
        txList.getEnvelopes().add(new EnvelopeTransactionList());

        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        assertEquals(1, result.getRemovedCount());
        assertEquals(0, result.getRemainingCount());
    }

    @Test
    void testProcessDeliveryResultsAllFailed() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(new ArrayList<>(List.of("tony@example.com", "pepper@example.com")));
        session.addEnvelope(envelope);

        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
        envTxList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "550 Mailbox unavailable", true);
        session.getSessionTransactionList().getEnvelopes().add(envTxList);

        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        assertEquals(0, result.getRemovedCount());
        assertEquals(1, result.getRemainingCount());
    }

    @Test
    void testProcessDeliveryResultsPartialSuccess() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(new ArrayList<>(List.of("tony@example.com", "pepper@example.com")));
        session.addEnvelope(envelope);

        EnvelopeTransactionList envTxList = new EnvelopeTransactionList();
        envTxList.addTransaction("RCPT", "RCPT TO:<tony@example.com>", "550 Mailbox unavailable", true);
        envTxList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "250 OK");
        session.getSessionTransactionList().getEnvelopes().add(envTxList);

        RelayDeliveryResult result = relayDequeue.processDeliveryResults(relaySession);

        assertEquals(0, result.getRemovedCount());
        assertEquals(1, result.getRemainingCount());
        assertEquals(1, envelope.getRcpts().size());
        assertTrue(envelope.getRcpts().contains("tony@example.com"));
    }

    @Test
    void testCleanupSuccessfulEnvelopesDeletesFiles() throws Exception {
        File tempFile = tempDir.resolve("test-envelope.eml").toFile();
        Files.writeString(tempFile.toPath(), "test content");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile(tempFile.getAbsolutePath());

        relayDequeue.cleanupSuccessfulEnvelopes(List.of(envelope));

        assertFalse(Files.exists(tempFile.toPath()));
    }

    @Test
    void testCleanupSuccessfulEnvelopesHandlesNonExistentFiles() {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile("/nonexistent/file.eml");

        assertDoesNotThrow(() -> relayDequeue.cleanupSuccessfulEnvelopes(List.of(envelope)));
    }

    @Test
    void testCleanupSuccessfulEnvelopesHandlesNullFile() {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile(null);

        assertDoesNotThrow(() -> relayDequeue.cleanupSuccessfulEnvelopes(List.of(envelope)));
    }

    @Test
    void testDeliveryResultGettersWork() {
        List<MessageEnvelope> envelopes = new ArrayList<>();
        RelayDeliveryResult result = new RelayDeliveryResult(3, 2, envelopes);

        assertEquals(3, result.getRemovedCount());
        assertEquals(2, result.getRemainingCount());
        assertSame(envelopes, result.getSuccessfulEnvelopes());
    }

    @Test
    void testBumpRetryCountUpdatesLastRetryTime() {
        Session session = createTestSession();
        RelaySession relaySession = new RelaySession(session);

        long initialRetryTime = relaySession.getLastRetryTime();
        relaySession.bumpRetryCount();

        assertTrue(relaySession.getLastRetryTime() >= initialRetryTime);
        assertEquals(1, relaySession.getRetryCount());
    }

    private QueueItem<RelaySession> claimSingle(String uid) {
        long now = Instant.now().getEpochSecond();
        QueueItem<RelaySession> claimed = testQueue.claimReady(1, now, "test", now + 300).getFirst();
        assertEquals(uid, claimed.getUid());
        return claimed;
    }

    private RelaySession relaySessionWithEnvelope(String sessionUid, List<String> recipients) {
        Session session = new Session().setUID(sessionUid);
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setRcpts(new ArrayList<>(recipients));
        session.addEnvelope(envelope);
        return new RelaySession(session);
    }

    private Session createTestSession() {
        return new Session().setUID("test-uid-" + System.nanoTime());
    }

    private static class TestRelayDequeue extends RelayDequeue {
        private final Consumer<RelaySession> deliveryScript;
        private int generatedBounceCount;

        private TestRelayDequeue(PersistentQueue<RelaySession> queue, Consumer<RelaySession> deliveryScript) {
            super(queue);
            this.deliveryScript = deliveryScript;
        }

        @Override
        void attemptDelivery(RelaySession relaySession) {
            if (deliveryScript != null) {
                deliveryScript.accept(relaySession);
            }
        }

        @Override
        List<RelaySession> generateBounces(RelaySession relaySession) {
            generatedBounceCount += countRecipients(relaySession);
            return List.of();
        }
    }

    private static class TestPooledLmtpDelivery extends PooledLmtpDelivery {
        private int invocations;

        @Override
        public boolean deliver(Session sourceSession, long maxAttempts, int retryDelaySeconds) {
            invocations++;
            sourceSession.getSessionTransactionList().clear();
            sourceSession.getSessionTransactionList().addEnvelope(new EnvelopeTransactionList());
            return true;
        }
    }
}
