package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

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
}
