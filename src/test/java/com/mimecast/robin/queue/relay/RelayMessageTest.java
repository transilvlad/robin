package com.mimecast.robin.queue.relay;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.InMemoryQueueDatabase;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import javax.naming.ConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class RelayMessageTest {

    private PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void beforeAll() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @BeforeEach
    void setUp() {
        PersistentQueue.getInstance().close();
        Factories.setQueueDatabase(InMemoryQueueDatabase::new);
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.clear();
            queue.close();
        }
        Factories.setQueueDatabase(null);
        var relay = Config.getServer().getRelay().getMap();
        relay.remove("inboundEnabled");
        relay.remove("outboundEnabled");
        relay.remove("host");
    }

    private Connection inboundConnection(RefCountedFileMessageSource source, Path file) {
        Session session = new Session();
        session.setUID("relay-test-" + System.nanoTime());
        session.setDirection(EmailDirection.INBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .setRcpts(new ArrayList<>(List.of("user@example.com")));
        envelope.setFile(file.toString());
        envelope.setMessageSource(source);
        session.addEnvelope(envelope);
        return new Connection(session);
    }

    @Test
    void inboundRelayOffByDefaultDoesNotEnqueueOrAcquire() throws Exception {
        var relay = Config.getServer().getRelay().getMap();
        relay.put("enabled", true);      // master switch on (queue workers)
        relay.remove("inboundEnabled");  // absent -> false
        relay.put("outboundEnabled", false);

        Path file = Files.createTempFile("relay-off-", ".eml");
        Files.writeString(file, "body");
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(file);

        new RelayMessage(inboundConnection(source, file), null).relay();

        assertEquals(0, queue.size(), "inbound relay must not enqueue when inboundEnabled is off");
        assertEquals(1, source.getRefCount(), "no clone should be acquired when inbound relay is off");
    }

    @Test
    void inboundRelayReleasesClonedReferenceAfterEnqueue() throws Exception {
        var relay = Config.getServer().getRelay().getMap();
        relay.put("enabled", true);
        relay.put("inboundEnabled", true);
        relay.put("outboundEnabled", false);
        relay.put("host", "relay.example.com");
        relay.put("port", 25);
        relay.put("protocol", "esmtp");

        Path file = Files.createTempFile("relay-on-", ".eml");
        Files.writeString(file, "body");
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(file);

        new RelayMessage(inboundConnection(source, file), null).relay();

        assertEquals(1, queue.size(), "inbound relay must enqueue one session");
        assertEquals(1, source.getRefCount(), "the clone's reference must be released after enqueue");
    }
}
