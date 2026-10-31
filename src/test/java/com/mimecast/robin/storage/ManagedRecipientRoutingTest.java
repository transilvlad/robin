package com.mimecast.robin.storage;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueListFilter;
import com.mimecast.robin.queue.relay.RelayMessage;
import com.mimecast.robin.sasl.RecipientDirectory;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.extension.server.ServerRcpt;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class ManagedRecipientRoutingTest {
    @TempDir Path directory;
    private ConnectionMock connection;
    private PersistentQueue<com.mimecast.robin.queue.RelaySession> queue;

    @BeforeEach
    void setUp() throws Exception {
        ConfigLoader.load("src/test/resources/cfg/");
        Config.getServer().getMap().put("recipientRouting", Map.of("enabled", true));
        Config.getServer().getMap().put("relay", Map.of("enabled", true, "outboundEnabled", true,
                "outboundMxEnabled", true, "disableRelayHeader", true));
        Config.getServer().getDovecot().getMap().put("saveLmtp", Map.of("enabled", true, "inline", true));
        Config.getServer().getDovecot().getMap().put("saveLda", Map.of("enabled", false));
        connection = new ConnectionMock(new StringBuilder());
        connection.getSession().setDirection(EmailDirection.INBOUND);
        connection.getSession().addEnvelope(new MessageEnvelope().setMail("author@example.net"));
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var item : queue.list(0, 100, QueueListFilter.activeOnly()).items()) {
            for (var envelope : item.getPayload().getSession().getEnvelopes()) {
                if (envelope.getFile() != null) Files.deleteIfExists(Path.of(envelope.getFile()));
            }
        }
        queue.clear();
        queue.close();
        connection.getSession().close();
        ConfigLoader.load("src/test/resources/cfg/");
    }

    @Test
    void inboundAliasLocalCopyAndForwardSurviveInlineMailboxDeliveryAndQueueOnlyExternalTargets() throws Exception {
        var router = router(false);
        assertTrue(rcpt(router).process(connection, new Verb("RCPT TO:<team@example.com>")));
        var envelope = connection.getSession().getEnvelopes().getLast();
        Path original = directory.resolve("message.eml");
        Files.writeString(original, "From: author@example.net\r\nSubject: test\r\n\r\nbody\r\n");
        envelope.setFile(original.toString()).setMessageSource(new RefCountedFileMessageSource(original));
        var previous = new MessageEnvelope().setMail("previous@example.com").setBytes(new byte[]{1});
        connection.getSession().getEnvelopes().addFirst(previous);
        var delivery = new PooledLmtpDelivery() {
            @Override public boolean deliver(Session local, long attempts, int wait) {
                assertEquals(List.of("alice@example.com"), local.getEnvelopes().getFirst().getRcpts());
                assertNotSame(connection.getSession(), local);
                var transaction = new EnvelopeTransactionList();
                transaction.addTransaction("DATA", "250 Delivered");
                local.getSessionTransactionList().addEnvelope(transaction);
                return true;
            }
        };
        assertTrue(new DovecotStorageProcessor(delivery).process(connection, null));
        assertSame(envelope, connection.getSession().getEnvelopes().getLast());
        assertSame(previous, connection.getSession().getEnvelopes().getFirst());
        assertEquals(2, envelope.getRcpts().size());
        new RelayMessage(connection).relay();
        var items = queue.list(0, 10, QueueListFilter.activeOnly()).items();
        assertEquals(1, items.size());
        assertEquals("mx", items.getFirst().getPayload().getProtocol());
        assertEquals(List.of("external@example.net"),
                items.getFirst().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(Files.exists(original), "The receipt must retain its source until its own close");
        connection.getSession().close();
        envelope.setMessageSource(null).setFile(null);
        assertFalse(Files.exists(original));
    }

    @Test
    void failedExpansionOrDirectoryOutageDoesNotMutatePreviouslyAcceptedRecipients() throws Exception {
        var envelope = connection.getSession().getEnvelopes().getLast();
        assertTrue(rcpt(router(false)).process(connection, new Verb("RCPT TO:<alice@example.com>")));
        var before = List.copyOf(envelope.getRcpts());
        assertFalse(rcpt(router(true)).process(connection, new Verb("RCPT TO:<team@example.com>")));
        assertEquals(before, envelope.getRcpts());
        assertTrue(connection.getOutput().contains("451 4.3.0"));
    }

    @Test
    void absentOutboundTransportAndDisabledForwardingRejectBeforeAcceptance() throws Exception {
        Config.getServer().getMap().put("relay", Map.of("enabled", false));
        assertFalse(rcpt(router(false)).process(connection, new Verb("RCPT TO:<team@example.com>")));
        assertTrue(connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty());
        assertTrue(connection.getOutput().contains("451 4.3.0"));
    }

    @Test
    void inlineLocalFailureQueuesOriginalInMemoryContentAndDoesNotEraseForwardTargets() throws Exception {
        assertTrue(rcpt(router(false)).process(connection, new Verb("RCPT TO:<team@example.com>")));
        var envelope = connection.getSession().getEnvelopes().getLast();
        byte[] content = "From: author@example.net\r\n\r\noriginal body\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        envelope.setBytes(content);
        var delivery = new PooledLmtpDelivery() {
            @Override public boolean deliver(Session local, long attempts, int wait) {
                var transaction = new EnvelopeTransactionList();
                transaction.addTransaction("RCPT", "RCPT TO:<alice@example.com>", "451 Try later", true);
                local.getSessionTransactionList().addEnvelope(transaction);
                return false;
            }
        };
        assertTrue(new DovecotStorageProcessor(delivery).process(connection, null));
        assertSame(envelope, connection.getSession().getEnvelopes().getLast());
        new RelayMessage(connection).relay();
        var items = queue.list(0, 10, QueueListFilter.activeOnly()).items();
        assertEquals(2, items.size());
        for (var item : items) {
            assertArrayEquals(content, item.getPayload().getSession().getEnvelopes().getFirst().readMessageBytes());
        }
    }

    @Test
    void inlineManagedLmtpBouncesUseDistinctFinalDiagnosticsAndDoNotBounceConfirmedRecipient() throws Exception {
        Config.getServer().getDovecot().getMap().put("failureBehaviour", "bounce");
        var router = new RecipientDirectory(new RecipientDirectory.Lookup() {
            @Override public Optional<RecipientDirectory.DomainPolicy> findDomain(String domain) {
                return Optional.of(new RecipientDirectory.DomainPolicy(true, false, null));
            }
            @Override public List<String> findAliases(String address) {
                return address.equals("team@example.com")
                        ? List.of("alice@example.com", "bob@example.com", "carol@example.com", "unknown@example.com") : List.of();
            }
            @Override public boolean hasMailbox(String address) { return !address.equals("team@example.com"); }
        }, false, 10, 100);
        assertTrue(rcpt(router).process(connection, new Verb("RCPT TO:<team@example.com>")));
        connection.getSession().getEnvelopes().getLast().setBytes(new byte[]{1});
        var delivery = new PooledLmtpDelivery() {
            @Override public boolean deliver(Session local, long attempts, int wait) {
                var transaction = new EnvelopeTransactionList();
                transaction.addTransaction("LMTP", "RCPT TO:<alice@example.com>", "250 Delivered");
                transaction.addTransaction("LMTP", "RCPT TO:<bob@example.com>", "550 5.1.1 Bob missing", true);
                transaction.addTransaction("LMTP", "RCPT TO:<carol@example.com>", "451 4.2.0 Carol deferred", true);
                local.getSessionTransactionList().addEnvelope(transaction);
                return false;
            }
        };
        assertTrue(new DovecotStorageProcessor(delivery).process(connection, null));
        var items = queue.list(0, 10, QueueListFilter.activeOnly()).items();
        assertEquals(3, items.size());
        var reports = new java.util.ArrayList<String>();
        for (var item : items) {
            var envelope = item.getPayload().getSession().getEnvelopes().getFirst();
            assertEquals("", envelope.getMail());
            assertEquals(List.of("author@example.net"), envelope.getRcpts());
            reports.add(new String(envelope.readMessageBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(reports.stream().anyMatch(report -> report.contains("Final-Recipient: RFC822; bob@example.com")
                && report.contains("Diagnostic-Code: SMTP; 550 5.1.1 Bob missing")));
        assertTrue(reports.stream().anyMatch(report -> report.contains("Final-Recipient: RFC822; carol@example.com")
                && report.contains("Diagnostic-Code: SMTP; 451 4.2.0 Carol deferred")));
        assertTrue(reports.stream().anyMatch(report -> report.contains("Final-Recipient: RFC822; unknown@example.com")
                && report.contains("Diagnostic-Code: SMTP; 451 4.4.2 Delivery not confirmed")));
        assertTrue(reports.stream().noneMatch(report -> report.contains("Final-Recipient: RFC822; alice@example.com")));
    }

    @Test
    void managedStalwartLocalIngestAlsoPreservesSubmittedForwardTargets() throws Exception {
        Config.getServer().getStalwart().getMap().put("enabled", true);
        Config.getServer().getStalwart().getMap().put("inline", true);
        connection.getSession().setDirection(EmailDirection.OUTBOUND).setAuth(true);
        assertTrue(rcpt(router(false)).process(connection, new Verb("RCPT TO:<team@example.com>")));
        var envelope = connection.getSession().getEnvelopes().getLast();
        envelope.setBytes(new byte[]{1, 2, 3});
        var delivery = new StalwartDirectDelivery() {
            @Override public boolean deliver(Session local, long attempts, int wait) {
                assertEquals(List.of("alice@example.com"), local.getEnvelopes().getFirst().getRcpts());
                var transaction = new EnvelopeTransactionList();
                transaction.addTransaction("DATA", "250 Delivered");
                local.getSessionTransactionList().addEnvelope(transaction);
                return true;
            }
        };
        assertTrue(new StalwartStorageProcessor(delivery).process(connection, null));
        assertSame(envelope, connection.getSession().getEnvelopes().getLast());
        assertEquals(2, envelope.getRcpts().size());
        new RelayMessage(connection).relay();
        assertEquals(1, queue.size());
    }

    private ServerRcpt rcpt(RecipientDirectory router) {
        return new ServerRcpt() {
            @Override protected RecipientDirectory getRecipientDirectory() { return router; }
        };
    }

    private RecipientDirectory router(boolean fail) {
        return new RecipientDirectory(new RecipientDirectory.Lookup() {
            @Override public Optional<RecipientDirectory.DomainPolicy> findDomain(String domain) {
                if (fail) throw new IllegalStateException("Synthetic database outage");
                return domain.equals("example.com")
                        ? Optional.of(new RecipientDirectory.DomainPolicy(true, false, null)) : Optional.empty();
            }
            @Override public List<String> findAliases(String address) {
                return address.equals("team@example.com")
                        ? List.of("alice@example.com", "external@example.net") : List.of();
            }
            @Override public boolean hasMailbox(String address) { return address.equals("alice@example.com"); }
        }, true, 10, 100);
    }
}
