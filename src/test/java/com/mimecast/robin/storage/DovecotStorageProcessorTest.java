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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class DovecotStorageProcessorTest {

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

        Config.getServer().getDovecot().getMap().put("failureBehaviour", "retry");
        Config.getServer().getDovecot().getMap().put("saveLmtp", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", false
        )));
        Config.getServer().getDovecot().getMap().put("saveLda", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", true,
                "inline", false,
                "ldaBinary", "/usr/lib/dovecot/dovecot-lda",
                "inboxFolder", "INBOX",
                "sentFolder", "Sent",
                "maxConcurrency", 32,
                "ldaTimeoutSeconds", 30
        )));
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.clear();
            queue.close();
        }
        Factories.setQueueDatabase(null);
    }

    @Test
    void queuedLdaModeEnqueuesPrimaryDelivery() throws Exception {
        DovecotStorageProcessor processor = new DovecotStorageProcessor();
        Connection connection = createInboundConnection(List.of("user01@example.com", "user02@example.com"));

        boolean result = processor.process(connection, null);
        QueueItem<RelaySession> queued = queue.list(0, 10, QueueListFilter.activeOnly()).items().getFirst();

        assertTrue(result);
        assertEquals(1, queue.size());
        assertEquals("dovecot-lda", queued.getProtocol());
        assertEquals("INBOX", queued.getPayload().getMailbox());
        assertEquals(List.of("user01@example.com", "user02@example.com"),
                queued.getPayload().getSession().getEnvelopes().getFirst().getRcpts());
    }

    @Test
    void lmtpDisabledNeverQueuesLmtpProtocol() throws Exception {
        // Explicitly disable LMTP, enable LDA
        Config.getServer().getDovecot().getMap().put("saveLmtp", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", false,
                "inline", false
        )));
        Config.getServer().getDovecot().getMap().put("saveLda", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", true,
                "inline", false,
                "ldaBinary", "/usr/lib/dovecot/dovecot-lda",
                "inboxFolder", "INBOX"
        )));

        DovecotStorageProcessor processor = new DovecotStorageProcessor();
        Connection connection = createInboundConnection(List.of("user@example.com"));

        processor.process(connection, null);

        // Verify no queue items have "lmtp" protocol
        List<QueueItem<RelaySession>> items = queue.list(0, 100, QueueListFilter.activeOnly()).items();
        for (QueueItem<RelaySession> item : items) {
            assertTrue(!item.getProtocol().equals("lmtp"),
                    "Found LMTP queue item when LMTP is disabled: " + item.getProtocol());
        }
    }

    @Test
    void lmtpEnabledQueuesLmtpProtocol() throws Exception {
        // Enable LMTP with queued (non-inline) mode
        Config.getServer().getDovecot().getMap().put("saveLmtp", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", true,
                "inline", false,
                "servers", List.of("localhost:24")
        )));
        Config.getServer().getDovecot().getMap().put("saveLda", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", false
        )));

        DovecotStorageProcessor processor = new DovecotStorageProcessor();
        Connection connection = createInboundConnection(List.of("user@example.com"));

        processor.process(connection, null);

        assertEquals(1, queue.size());
        QueueItem<RelaySession> queued = queue.list(0, 10, QueueListFilter.activeOnly()).items().getFirst();
        assertEquals("lmtp", queued.getProtocol());
    }

    @Test
    void bothBackendsDisabledQueuesNothing() throws Exception {
        Config.getServer().getDovecot().getMap().put("saveLmtp", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", false
        )));
        Config.getServer().getDovecot().getMap().put("saveLda", new java.util.LinkedHashMap<>(java.util.Map.of(
                "enabled", false
        )));

        DovecotStorageProcessor processor = new DovecotStorageProcessor();
        Connection connection = createInboundConnection(List.of("user@example.com"));

        processor.process(connection, null);

        assertEquals(0, queue.size());
    }

    @Test
    void inlineLmtpRetriesFailedAndUnknownRecipientsButNotConfirmedDelivery() throws Exception {
        Config.getServer().getDovecot().getMap().put("saveLmtp", java.util.Map.of("enabled", true, "inline", true));
        var connection = createInboundConnection(List.of("first@example.com", "failed@example.com", "unknown@example.com"));
        Path original = Path.of(connection.getSession().getEnvelopes().getFirst().getFile());
        var delivery = new PooledLmtpDelivery() {
            @Override public boolean deliver(Session session, long attempts, int delay) {
                var tx = new EnvelopeTransactionList();
                for (String recipient : session.getEnvelopes().getFirst().getRcpts()) {
                    tx.addTransaction("RCPT", "RCPT TO:<" + recipient + ">", "250 Admitted");
                }
                tx.addTransaction("LMTP", "RCPT TO:<first@example.com>", "250 Delivered");
                tx.addTransaction("LMTP", "RCPT TO:<failed@example.com>", "550 Missing", true);
                session.getSessionTransactionList().addEnvelope(tx);
                return false;
            }
        };
        try {
            new DovecotStorageProcessor(delivery).saveToLmtp(connection, null, Config.getServer());
            var queued = queue.list(0, 10, QueueListFilter.activeOnly()).items();
            var recipients = queued.stream().flatMap(item -> item.getPayload().getSession().getEnvelopes().getFirst()
                    .getRcpts().stream()).sorted().toList();
            assertEquals(List.of("failed@example.com", "unknown@example.com"), recipients);
            for (var item : queued) {
                assertTrue(new String(item.getPayload().getSession().getEnvelopes().getFirst().readMessageBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).contains("Subject: Test"));
            }
        } finally {
            for (var item : queue.list(0, 10, QueueListFilter.activeOnly()).items()) {
                Files.deleteIfExists(Path.of(item.getPayload().getSession().getEnvelopes().getFirst().getFile()));
            }
            Files.deleteIfExists(original);
        }
    }

    private Connection createInboundConnection(List<String> recipients) throws IOException {
        Session session = new Session();
        session.setUID("dovecot-lda-test-" + System.nanoTime());
        session.setDirection(EmailDirection.INBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .setRcpts(new ArrayList<>(recipients));
        Path tempFile = Files.createTempFile("dovecot-lda-storage-", ".eml");
        Files.writeString(tempFile, "From: sender@example.com\r\nTo: user@example.com\r\nSubject: Test\r\n\r\nBody\r\n");
        envelope.setFile(tempFile.toString());
        session.addEnvelope(envelope);
        return new Connection(session);
    }

    @Test
    void filterLocalRecipientsKeepsOnlyLocalUsers() {
        List<String> filtered = DovecotStorageProcessor.filterLocalRecipients(
                List.of("tony@example.com", "external@example.org", "pepper@example.com"),
                recipient -> recipient.endsWith("@example.com"));
        assertEquals(List.of("tony@example.com", "pepper@example.com"), filtered);
    }

    @Test
    void filterLocalRecipientsKeepsRecipientsWhenLookupFails() {
        List<String> filtered = DovecotStorageProcessor.filterLocalRecipients(
                List.of("tony@example.com", "external@example.org"),
                recipient -> {
                    throw new IllegalStateException("SQL user lookup unavailable");
                });
        assertEquals(List.of("tony@example.com", "external@example.org"), filtered);
    }
}