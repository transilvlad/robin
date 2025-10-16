package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelayQueueCron;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageClientTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/");
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
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("vmarian@mimecast.com");
        connection.getSession().addEnvelope(envelope);
        LocalStorageClient localStorageClient = new LocalStorageClient()
                .setConnection(connection)
                .setExtension("dat");

        assertTrue(localStorageClient.getFile().contains("/tmp/store/mimecast.com/vmarian/") || localStorageClient.getFile().contains("\\tmp\\store\\mimecast.com\\vmarian\\"));
        assertTrue(localStorageClient.getFile().contains(new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date()) + "."));
        assertTrue(localStorageClient.getFile().contains(".dat"));
    }

    @Test
    void stream() throws IOException {
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("vmarian@mimecast.com");
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
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("vmarian@mimecast.com");
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
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("vmarian@mimecast.com");
        connection.getSession().addEnvelope(envelope);

        BasicConfig config = new BasicConfig(new HashMap<>(Config.getServer().getStorage().getMap()));
        config.getMap().put("saveToDovecotLda", true);

        LocalStorageClient localStorageClient = new LocalStorageClientMock(config, Pair.of(param, ""))
                .setConnection(new ConnectionMock(Factories.getSession()))
                .setExtension("dat").setConnection(connection);

        String content = "Mime-Version: 1.0\r\n";
        localStorageClient.getStream().write(content.getBytes());

        assertTrue(localStorageClient.getFile().endsWith(".dat"));

        localStorageClient.save();

        assertEquals(content, PathUtils.readFile(localStorageClient.getFile(), Charset.defaultCharset()));
        assertTrue(new File(localStorageClient.getFile()).delete());

        if (param != 0) {
            PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE).dequeue();
        }
    }
}
