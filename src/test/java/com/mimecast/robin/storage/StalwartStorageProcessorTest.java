package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.InMemoryQueueDatabase;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueItem;
import com.mimecast.robin.queue.QueueListFilter;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class StalwartStorageProcessorTest {

    private PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void beforeAll() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @BeforeEach
    void setUp() {
        PersistentQueue<RelaySession> existing = PersistentQueue.getInstance();
        existing.close();
        Factories.setQueueDatabase(InMemoryQueueDatabase::new);
        queue = PersistentQueue.getInstance();
        queue.clear();

        Config.getServer().getStalwart().getMap().put("enabled", false);
        Config.getServer().getStalwart().getMap().put("inline", true);
        Config.getServer().getStalwart().getMap().put("failureBehaviour", "retry");
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.clear();
            queue.close();
        }
        Factories.setQueueDatabase(null);
        Config.getServer().getStalwart().getMap().put("enabled", false);
    }

    @Test
    void processSkipsWhenDisabled() throws Exception {
        StubStalwartDirectDelivery delivery = new StubStalwartDirectDelivery(session -> true);
        StalwartStorageProcessor processor = new StalwartStorageProcessor(delivery);
        Connection connection = createInboundConnection(List.of("user01@example.com"));

        boolean result = processor.process(connection, null);

        assertTrue(result);
        assertEquals(0, delivery.invocations);
        assertEquals(0, queue.size());
    }

    @Test
    void inlineSuccessDoesNotQueueRetries() throws Exception {
        Config.getServer().getStalwart().getMap().put("enabled", true);
        StubStalwartDirectDelivery delivery = new StubStalwartDirectDelivery(session -> {
            EnvelopeTransactionList envelopeTransactions = new EnvelopeTransactionList();
            envelopeTransactions.addTransaction("MAIL", "FROM:<sender@example.com>", "250 2.1.0 Sender OK");
            envelopeTransactions.addTransaction("RCPT", "TO:<user01@example.com>", "250 2.1.5 Recipient OK");
            envelopeTransactions.addTransaction("DATA", "250 2.0.0 Message imported");
            session.getSessionTransactionList().addEnvelope(envelopeTransactions);
            return true;
        });
        StalwartStorageProcessor processor = new StalwartStorageProcessor(delivery);
        Connection connection = createInboundConnection(List.of("user01@example.com"));

        boolean result = processor.process(connection, null);

        assertTrue(result);
        assertEquals(1, delivery.invocations);
        assertEquals(0, queue.size());
    }

    @Test
    void inlinePartialFailureQueuesOnlyFailedRecipients() throws Exception {
        Config.getServer().getStalwart().getMap().put("enabled", true);
        Config.getServer().getStalwart().getMap().put("failureBehaviour", "retry");
        StubStalwartDirectDelivery delivery = new StubStalwartDirectDelivery(session -> {
            EnvelopeTransactionList envelopeTransactions = new EnvelopeTransactionList();
            envelopeTransactions.addTransaction("MAIL", "FROM:<sender@example.com>", "250 2.1.0 Sender OK");
            envelopeTransactions.addTransaction("RCPT", "TO:<user01@example.com>", "250 2.1.5 Recipient OK");
            envelopeTransactions.addTransaction("RCPT", "TO:<user02@example.com>", "550 5.1.1 mailbox missing", true);
            envelopeTransactions.addTransaction("DATA", "250 2.0.0 Message partially imported");
            session.getSessionTransactionList().addEnvelope(envelopeTransactions);
            return false;
        });
        StalwartStorageProcessor processor = new StalwartStorageProcessor(delivery);
        Connection connection = createInboundConnection(List.of("user01@example.com", "user02@example.com"));

        boolean result = processor.process(connection, null);
        QueueItem<RelaySession> queued = queue.list(0, 10, QueueListFilter.activeOnly()).items().getFirst();

        assertTrue(result);
        assertEquals(1, queue.size());
        assertEquals("stalwart-direct", queued.getProtocol());
        assertEquals(List.of("user02@example.com"), queued.getPayload().getSession().getEnvelopes().getFirst().getRcpts());
    }

    @Test
    void queuedModeEnqueuesAllRecipientsWithoutInlineDelivery() throws Exception {
        Config.getServer().getStalwart().getMap().put("enabled", true);
        Config.getServer().getStalwart().getMap().put("inline", false);
        StubStalwartDirectDelivery delivery = new StubStalwartDirectDelivery(session -> true);
        StalwartStorageProcessor processor = new StalwartStorageProcessor(delivery);
        Connection connection = createInboundConnection(List.of("user01@example.com", "user02@example.com"));

        boolean result = processor.process(connection, null);
        QueueItem<RelaySession> queued = queue.list(0, 10, QueueListFilter.activeOnly()).items().getFirst();

        assertTrue(result);
        assertEquals(0, delivery.invocations);
        assertEquals(1, queue.size());
        assertEquals("stalwart-direct", queued.getProtocol());
        assertEquals(List.of("user01@example.com", "user02@example.com"),
                queued.getPayload().getSession().getEnvelopes().getFirst().getRcpts());
    }

    private Connection createInboundConnection(List<String> recipients) throws IOException {
        Session session = new Session();
        session.setUID("stalwart-test-" + System.nanoTime());
        session.setDirection(EmailDirection.INBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .setRcpts(new ArrayList<>(recipients));
        Path tempFile = Files.createTempFile("stalwart-storage-", ".eml");
        Files.writeString(tempFile, "From: sender@example.com\r\nTo: user@example.com\r\nSubject: Test\r\n\r\nBody\r\n");
        envelope.setFile(tempFile.toString());
        session.addEnvelope(envelope);
        return new Connection(session);
    }

    private static final class StubStalwartDirectDelivery extends StalwartDirectDelivery {
        private final Function<Session, Boolean> resultFactory;
        private int invocations;

        private StubStalwartDirectDelivery(Function<Session, Boolean> resultFactory) {
            super((com.mimecast.robin.storage.stalwart.StalwartApiClient) null);
            this.resultFactory = resultFactory;
        }

        @Override
        public boolean deliver(Session sourceSession, long maxAttempts, int retryDelaySeconds) {
            invocations++;
            sourceSession.getSessionTransactionList().clear();
            return resultFactory.apply(sourceSession);
        }
    }
}
