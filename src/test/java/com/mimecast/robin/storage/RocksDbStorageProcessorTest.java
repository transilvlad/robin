package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.storage.rocksdb.InMemoryMailboxStore;
import com.mimecast.robin.storage.rocksdb.MailboxStore;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStoreManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "mailbox-store-factory", mode = ResourceAccessMode.READ_WRITE)
class RocksDbStorageProcessorTest {

    private Path sourceFile;
    private MailboxStore mockStore;

    @BeforeAll
    static void beforeAll() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @BeforeEach
    void setUp() {
        mockStore = new InMemoryMailboxStore("Inbox", "Sent");
        RocksDbMailboxStoreManager.setStoreFactory(() -> mockStore);

        Map<String, Object> rocksDb = new HashMap<>();
        rocksDb.put("enabled", true);
        rocksDb.put("path", "memory");
        rocksDb.put("inboxFolder", "Inbox");
        rocksDb.put("sentFolder", "Sent");
        Config.getServer().getStorage().getMap().put("rocksdb", rocksDb);
    }

    @AfterEach
    void tearDown() throws IOException {
        RocksDbMailboxStoreManager.closeAll();
        if (sourceFile != null) {
            Files.deleteIfExists(sourceFile);
        }
        Config.getServer().getStorage().getMap().remove("rocksdb");
    }

    @Test
    void storesInboundAndOutboundMessagesIntoRocksDb() throws Exception {

        RocksDbStorageProcessor processor = new RocksDbStorageProcessor();

        sourceFile = Files.createTempFile("robin-rocksdb-message-", ".eml");
        Files.writeString(sourceFile,
                "From: sender@example.com\r\nTo: user@example.com\r\nSubject: Processor\r\n\r\nBody",
                StandardCharsets.UTF_8);

        Connection inbound = new Connection(new Session());
        inbound.getSession().setDirection(EmailDirection.INBOUND);
        MessageEnvelope inboundEnvelope = new MessageEnvelope().addRcpt("user@example.com");
        inboundEnvelope.setFile(sourceFile.toString());
        inbound.getSession().addEnvelope(inboundEnvelope);

        try (EmailParser parser = new EmailParser(sourceFile.toString()).parse()) {
            assertTrue(processor.process(inbound, parser));
        }

        var store = RocksDbMailboxStoreManager.getConfiguredStore();
        var inbox = store.getFolder("example.com", "user", "Inbox", "unread");
        assertEquals(1, inbox.messages.size());
        var inboxMessage = store.getMessage("example.com", "user", inbox.messages.getFirst().id).orElseThrow();
        assertTrue(inboxMessage.content.contains("Received:"));
        assertTrue(inboxMessage.content.contains("for <user@example.com>"));

        Connection outbound = new Connection(new Session());
        outbound.getSession().setDirection(EmailDirection.OUTBOUND);
        MessageEnvelope outboundEnvelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .addRcpt("remote@example.net");
        outboundEnvelope.setFile(sourceFile.toString());
        outbound.getSession().addEnvelope(outboundEnvelope);

        try (EmailParser parser = new EmailParser(sourceFile.toString()).parse()) {
            assertTrue(processor.process(outbound, parser));
        }

        var sent = store.getFolder("example.com", "sender", "Sent", "read");
        assertEquals(1, sent.messages.size());
    }

    @Test
    void storesInboundMessagesFromInMemorySource() throws Exception {
        RocksDbStorageProcessor processor = new RocksDbStorageProcessor();
        String content = "From: sender@example.com\r\nTo: user@example.com\r\nSubject: Processor\r\n\r\nBody";

        Connection inbound = new Connection(new Session());
        inbound.getSession().setDirection(EmailDirection.INBOUND);
        MessageEnvelope inboundEnvelope = new MessageEnvelope()
                .addRcpt("user@example.com")
                .setBytes(content.getBytes(StandardCharsets.UTF_8))
                .setFile("planned-message.eml");
        inbound.getSession().addEnvelope(inboundEnvelope);

        try (EmailParser parser = new EmailParser(new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).parse()) {
            assertTrue(processor.process(inbound, parser));
        }

        var store = RocksDbMailboxStoreManager.getConfiguredStore();
        var inbox = store.getFolder("example.com", "user", "Inbox", "unread");
        assertEquals(1, inbox.messages.size());
        var inboxMessage = store.getMessage("example.com", "user", inbox.messages.getFirst().id).orElseThrow();
        assertTrue(inboxMessage.content.contains("Received:"));
        assertTrue(inboxMessage.content.contains("for <user@example.com>"));
    }
}
