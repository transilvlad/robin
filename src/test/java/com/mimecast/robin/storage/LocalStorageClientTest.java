package com.mimecast.robin.storage;

import com.mimecast.robin.bots.BotProcessor;
import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "storage-config", mode = ResourceAccessMode.READ_WRITE)
class LocalStorageClientTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void simple() {
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(new ConnectionMock(Factories.getSession()))
                .setExtension("dat");

        assertTrue(localStorageClient.getFile().contains(new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date()) + "."));
        assertTrue(localStorageClient.getFile().contains(".dat"));
    }

    @Test
    void connection() {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("dat");

        assertTrue(localStorageClient.getFile().contains(new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date()) + "."));
        assertTrue(localStorageClient.getFile().contains(".dat"));
    }

    @Test
    void stream() throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("eml");

        String content = "Mime-Version: 1.0\r\n";
        localStorageClient.getStream().write(content.getBytes());
        localStorageClient.save();

        assertNotNull(envelope.getMessageSource());
        assertEquals(content, new String(envelope.readMessageBytes(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(Path.of(localStorageClient.getFile())));
    }

    @Test
    void filename() throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("dat");

        String content = "Mime-Version: 1.0\r\n" +
                "X-Robin-Filename: robin.eml\r\n" +
                "\r\n";
        localStorageClient.getStream().write(content.getBytes());

        assertTrue(localStorageClient.getFile().endsWith(".dat"));

        localStorageClient.save();

        assertTrue(localStorageClient.getFile().endsWith("robin.eml"));
        assertNotNull(envelope.getMessageSource());
        assertEquals(content, new String(envelope.readMessageBytes(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(Path.of(localStorageClient.getFile())));
    }

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void saveToDovecotLda(int param) throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);

        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("dat");

        String content = "Mime-Version: 1.0\r\n";
        localStorageClient.getStream().write(content.getBytes());

        assertTrue(localStorageClient.getFile().endsWith(".dat"));

        localStorageClient.save();

        try { new File(localStorageClient.getFile()).delete(); } catch (Exception ignored) {}

        // Clean up the queue if we enqueued a bounce (uses in-memory queue from test config).
        if (param != 0) {
            try {
                PersistentQueue.getInstance().clear();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void botAddressForceSpillToFile() throws IOException {
        // Test that bot addresses force spill to file for thread-safe access.
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope()
                .addRcpt("dmarcReport@example.com")
                .addBotAddress("dmarcReport@example.com", "dmarc");
        connection.getSession().addEnvelope(envelope);

        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("eml");

        // Write a small email (below 1MB threshold).
        String content = "Mime-Version: 1.0\r\n" +
                "Content-Type: multipart/mixed; boundary=\"test\"\r\n" +
                "\r\n" +
                "--test\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Hello\r\n" +
                "--test--\r\n";
        localStorageClient.getStream().write(content.getBytes(StandardCharsets.UTF_8));

        assertTrue(envelope.hasBotAddresses(), "Should have bot addresses");

        localStorageClient.save();

        // Verify file was created (force spilled).
        assertTrue(Files.exists(Path.of(localStorageClient.getFile())), 
                "Bot address should force spill to file");
        
        // Verify message source is RefCountedFileMessageSource.
        assertNotNull(envelope.getMessageSource());
        assertTrue(envelope.getMessageSource() instanceof com.mimecast.robin.smtp.RefCountedFileMessageSource,
                "Should be RefCountedFileMessageSource for bot addresses, got: " + 
                envelope.getMessageSource().getClass().getSimpleName());

        // Verify content is correct.
        assertEquals(content, new String(envelope.readMessageBytes(), StandardCharsets.UTF_8));

        // Clean up.
        try { Files.deleteIfExists(Path.of(localStorageClient.getFile())); } catch (Exception ignored) {}
    }

    @Test
    void botAddressDispatch_submitsSameBotNameOnce() throws Exception {
        CountingBot bot = new CountingBot();
        Factories.registerBot(bot);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService previousExecutor = setBotExecutor(executor);
        LocalStorageClient localStorageClient = null;
        try {
            Connection connection = new Connection(new Session());
            MessageEnvelope envelope = new MessageEnvelope()
                    .addRcpt("counting+one@example.com")
                    .addRcpt("counting+two@example.com")
                    .addBotAddress("counting+one@example.com", "counting")
                    .addBotAddress("counting+two@example.com", "counting");
            connection.getSession().addEnvelope(envelope);

            localStorageClient = new LocalStorageClient()
                    .setConnection(connection)
                    .setExtension("eml");

            localStorageClient.getStream().write(("From: sender@example.com\r\n" +
                    "To: counting@example.com\r\n" +
                    "\r\n" +
                    "Hello\r\n").getBytes(StandardCharsets.UTF_8));

            assertTrue(localStorageClient.save());
            assertTrue(bot.await());
            assertEquals(1, bot.invocations());
            assertEquals("counting+one@example.com", bot.botAddress());
        } finally {
            executor.shutdownNow();
            setBotExecutor(previousExecutor);
            if (localStorageClient != null) {
                Files.deleteIfExists(Path.of(localStorageClient.getFile()));
            }
        }
    }

    private ExecutorService setBotExecutor(ExecutorService executor) throws ReflectiveOperationException {
        Field field = Server.class.getDeclaredField("botExecutor");
        field.setAccessible(true);
        ExecutorService previous = (ExecutorService) field.get(null);
        field.set(null, executor);
        return previous;
    }

    private static class CountingBot implements BotProcessor {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile String botAddress;

        @Override
        public void process(Connection connection, EmailParser emailParser, String botAddress, BotConfig.BotDefinition botDefinition) {
            this.botAddress = botAddress;
            invocations.incrementAndGet();
            latch.countDown();
        }

        @Override
        public String getName() {
            return "counting";
        }

        boolean await() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }

        int invocations() {
            return invocations.get();
        }

        String botAddress() {
            return botAddress;
        }
    }
}
