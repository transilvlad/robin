package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.storage.PooledLmtpDelivery;
import com.mimecast.robin.storage.StalwartDirectDelivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class StalwartDirectRelayDequeueTest {

    private PersistentQueue<RelaySession> queue;

    @BeforeEach
    void setUp() {
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.clear();
            queue.close();
        }
    }

    @Test
    void stalwartDirectProtocolUsesDirectDeliveryHelper() throws Exception {
        RelaySession relaySession = new RelaySession(new Session().setUID("stalwart-direct-test"));
        relaySession.setProtocol("stalwart-direct");
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .setRcpts(List.of("user01@example.com"));
        Path tempFile = Files.createTempFile("stalwart-direct-queue-", ".eml");
        Files.writeString(tempFile, "From: sender@example.com\r\nTo: user01@example.com\r\nSubject: Test\r\n\r\nBody\r\n");
        envelope.setFile(tempFile.toString());
        relaySession.getSession().addEnvelope(envelope);

        queue.enqueue(relaySession);
        long now = Instant.now().getEpochSecond();
        QueueItem<RelaySession> claimed = queue.claimReady(1, now, "test", now + 60).getFirst();

        StubStalwartDirectDelivery delivery = new StubStalwartDirectDelivery();
        RelayDequeue relayDequeue = new RelayDequeue(queue, new PooledLmtpDelivery(), delivery);
        RelayQueueWorkResult result = relayDequeue.processClaimedItem(claimed, now);
        queue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));

        assertEquals(1, delivery.invocations);
        assertEquals(0, queue.size());
        assertNull(queue.getByUID(relaySession.getUID()));
        assertTrue(result.cleanupPaths().contains(tempFile));
    }

    private static final class StubStalwartDirectDelivery extends StalwartDirectDelivery {
        private int invocations;

        private StubStalwartDirectDelivery() {
            super((com.mimecast.robin.storage.stalwart.StalwartApiClient) null);
        }

        @Override
        public boolean deliver(Session sourceSession, long maxAttempts, int retryDelaySeconds) {
            invocations++;
            EnvelopeTransactionList envelopeTransactions = new EnvelopeTransactionList();
            envelopeTransactions.addTransaction("MAIL", "FROM:<sender@example.com>", "250 2.1.0 Sender OK");
            envelopeTransactions.addTransaction("RCPT", "TO:<user01@example.com>", "250 2.1.5 Recipient OK");
            envelopeTransactions.addTransaction("DATA", "250 2.0.0 Message imported");
            sourceSession.getSessionTransactionList().addEnvelope(envelopeTransactions);
            return true;
        }
    }
}
