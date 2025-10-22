package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.PathUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(localStorageClient.getFile().contains("/tmp/store/") || localStorageClient.getFile().contains("\\tmp\\store\\"));
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

        assertTrue(localStorageClient.getFile().contains("/tmp/store/example.com/tony/") || localStorageClient.getFile().contains("\\tmp\\store\\example.com\\tony\\"));
        assertTrue(localStorageClient.getFile().contains(new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date()) + "."));
        assertTrue(localStorageClient.getFile().contains(".dat"));
    }

    @Test
    void stream() throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(new ConnectionMock(Factories.getSession()))
                .setExtension("eml").setConnection(connection);

        String content = "Mime-Version: 1.0\r\n";
        localStorageClient.getStream().write(content.getBytes());
        localStorageClient.save();

        assertEquals(content, PathUtils.readFile(localStorageClient.getFile(), Charset.defaultCharset()));
        assertTrue(new File(localStorageClient.getFile()).delete());
    }

    @Test
    void filename() throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(new ConnectionMock(Factories.getSession()))
                .setExtension("dat").setConnection(connection);

        String content = "Mime-Version: 1.0\r\n" +
                "X-Robin-Filename: robin.eml\r\n" +
                "\r\n";
        localStorageClient.getStream().write(content.getBytes());

        assertTrue(localStorageClient.getFile().endsWith(".dat"));

        localStorageClient.save();

        assertTrue(localStorageClient.getFile().endsWith("robin.eml"));
        assertEquals(content, PathUtils.readFile(localStorageClient.getFile(), Charset.defaultCharset()));
        assertTrue(new File(localStorageClient.getFile()).delete());
    }

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void saveToDovecotLda(int param) throws IOException {
        // Use a unique, non-existent queue file path for each run (MapDB should create it).
        Path tmpDir = Files.createTempDirectory("robinRelayQueue-");
        File tmpQueueFile = tmpDir.resolve("relayQueue-" + System.nanoTime() + ".db").toFile();

        // Override relay queue file in runtime config so production code writes to this file.
        Config.getServer().getQueue().getMap().put("queueFile", tmpQueueFile.getAbsolutePath());

        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);

        LocalStorageClient localStorageClient = new LocalStorageClientMock(Config.getServer(), Pair.of(param, ""))
                .setConnection(new ConnectionMock(Factories.getSession()))
                .setExtension("dat").setConnection(connection);

        String content = "Mime-Version: 1.0\r\n";
        localStorageClient.getStream().write(content.getBytes());

        assertTrue(localStorageClient.getFile().endsWith(".dat"));

        localStorageClient.save();

        try { new File(localStorageClient.getFile()).delete(); } catch (Exception ignored) {}

        // Clean up the temp queue file and any WALs if we enqueued a bounce.
        if (param != 0) {
            PersistentQueue.getInstance(tmpQueueFile).dequeue();
            try { PersistentQueue.getInstance(tmpQueueFile).close(); } catch (Exception ignored) {}
        }

        // Attempt to delete queue file and any potential WAL segments, then the temp directory.
        try { new File(tmpQueueFile.getAbsolutePath()).delete(); } catch (Exception ignored) {}
        for (int i = 0; i < 4; i++) {
            try { new File(tmpQueueFile.getAbsolutePath() + ".wal." + i).delete(); } catch (Exception ignored) {}
        }
        try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
    }
}
