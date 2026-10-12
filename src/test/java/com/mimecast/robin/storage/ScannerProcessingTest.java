package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.config.server.ProxyRule;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.scanners.ClamAVMockServer;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.ProxyEmailDelivery;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.extension.server.ServerData;
import com.mimecast.robin.smtp.verb.Verb;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class ScannerProcessingTest {
    @TempDir
    Path dir;

    private List<Callable<StorageProcessor>> originalProcessors;
    private Map<String, Object> originalConfig;
    private MockWebServer rspamd;
    private ClamAVMockServer clamav;
    private final AtomicInteger downstreamCalls = new AtomicInteger();
    private final AtomicInteger proxyCalls = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        Foundation.init("src/test/resources/cfg/");
        originalConfig = new HashMap<>(Config.getServer().getMap());
        ServerConfig fixture = new ServerConfig("src/test/resources/cfg/server.json5");
        Config.getServer().getMap().put("storage", fixture.getStorage().getMap());
        Config.getServer().getMap().put("relay", fixture.getRelay().getMap());
        Config.getServer().getMap().put("clamav", fixture.getClamAV().getMap());
        Config.getServer().getMap().put("rspamd", fixture.getRspamd().getMap());
        originalProcessors = new ArrayList<>(Factories.getStorageProcessors());
        rspamd = new MockWebServer();
        rspamd.start();
        try (ServerSocket port = new ServerSocket(0)) {
            clamav = new ClamAVMockServer(port.getLocalPort());
            Config.getServer().getClamAV().getMap().putAll(Map.of(
                    "host", "localhost", "port", port.getLocalPort(), "timeout", 1, "enabled", false));
        }
        assertTrue(clamav.start());
        Config.getServer().getMap().put("chaosHeaders", false);
        Config.getServer().getMap().put("renameHeaderEnabled", false);
        Config.getServer().getMap().put("webhooks", Map.of("raw", Map.of("enabled", true)));
        Config.getServer().getStorage().getMap().put("path", dir.toString());
        Config.getServer().getRspamd().getMap().putAll(Map.of(
                "host", "localhost", "port", rspamd.getPort(), "timeout", 1,
                "enabled", false, "rejectThreshold", 15.0, "discardThreshold", 25.0));
        Factories.setStorageProcessors(List.of(SpamStorageProcessor::new, AVStorageProcessor::new,
                () -> (connection, parser) -> {
                    downstreamCalls.incrementAndGet();
                    return true;
                }));
        Factories.setWebhookCaller(() -> { throw new AssertionError("Discarded/rejected message reached RAW webhook"); });
    }

    @AfterEach
    void cleanup() throws Exception {
        rspamd.shutdown();
        clamav.stop();
        Factories.setStorageProcessors(originalProcessors);
        Factories.setWebhookCaller(null);
        Config.getServer().getMap().clear();
        Config.getServer().getMap().putAll(originalConfig);
    }

    @ParameterizedTest
    @CsvSource({"14.99,true,true,false", "15.0,true,false,false",
            "24.99,true,false,false", "25.0,true,true,true", "100.0,false,false,false"})
    void spamThresholdBoundaries(double score, boolean discardEnabled, boolean accepted, boolean discarded) throws Exception {
        Config.getServer().getMap().put("webhooks", Map.of());
        Config.getServer().getRspamd().getMap().putAll(Map.of("enabled", true, "discardEnabled", discardEnabled));
        rspamd.enqueue(new MockResponse().setBody("{\"score\":" + score + ",\"symbols\":{}}"));
        ConnectionMock connection = receipt(false, "ordinary message");
        try {
            boolean result = new ServerData().process(connection, new Verb("DATA"));
            assertEquals(accepted, result);
            assertEquals(discarded, connection.getSession().getEnvelopes().getLast().isBlackholed());
            assertEquals(accepted && !discarded ? 1 : 0, downstreamCalls.get());
            assertEquals(1, connection.getOutput().lines().filter(line -> line.startsWith("250") || line.startsWith("541")).count());
            if (discarded) assertDiscarded(connection);
        } finally {
            connection.getSession().close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void scannerDiscardStopsDeliveryRelayWebhooksAndProxy(boolean antivirus, boolean binary) throws Exception {
        enableScanner(antivirus, true);
        Config.getServer().getStorage().getMap().put("messageBufferMaxBytes", 0);
        Config.getServer().getRelay().getMap().put("inboundEnabled", true);
        long queuedBefore = PersistentQueue.getInstance().size();
        ConnectionMock connection = receipt(binary, antivirus ? "SIMULATED_VIRUS" : "spam");
        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        if (!binary) {
            connection.getSession().getProxyConnections().put(new ProxyRule(Map.of("hosts", List.of("localhost"))), new ProxyEmailDelivery(connection.getSession(), envelope) {
                @Override
                public boolean isConnected() { return true; }
                @Override
                public boolean sendData() { proxyCalls.incrementAndGet(); return true; }
            });
        }
        try {
            assertTrue(new ServerData().process(connection, verb(binary, antivirus ? "SIMULATED_VIRUS" : "spam")));
            assertDiscarded(connection);
            assertEquals(0, downstreamCalls.get());
            assertEquals(0, proxyCalls.get());
            assertEquals(queuedBefore, PersistentQueue.getInstance().size());
            assertEquals(1, connection.getOutput().lines().filter(line -> line.startsWith("250")).count());
        } finally {
            connection.getSession().close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void scannerOutageReturnsOne451AndNoDelivery(boolean antivirus, boolean binary) throws Exception {
        enableScanner(antivirus, false);
        if (antivirus) {
            clamav.stop();
        } else {
            rspamd.shutdown();
        }
        ConnectionMock connection = receipt(binary, "ordinary message");
        try {
            assertFalse(new ServerData().process(connection, verb(binary, "ordinary message")));
            assertEquals(0, downstreamCalls.get());
            assertEquals(1, connection.getOutput().lines().filter(line -> line.startsWith("451 ")).count());
            assertFalse(connection.getOutput().contains("250 "));
        } finally {
            connection.getSession().close();
        }
    }

    @Test
    void invalidRspamdResponseReturns451RatherThanAccepting() throws Exception {
        Config.getServer().getRspamd().getMap().put("enabled", true);
        rspamd.enqueue(new MockResponse().setBody("{}"));
        ConnectionMock connection = receipt(false, "ordinary message");
        try {
            assertFalse(new ServerData().process(connection, new Verb("DATA")));
            assertEquals(0, downstreamCalls.get());
            assertEquals(1, connection.getOutput().lines().filter(line -> line.startsWith("451 ")).count());
        } finally {
            connection.getSession().close();
        }
    }

    private void enableScanner(boolean antivirus, boolean discard) {
        if (antivirus) {
            Config.getServer().getClamAV().getMap().putAll(Map.of("enabled", true, "onVirus", discard ? "discard" : "reject"));
        } else {
            Config.getServer().getRspamd().getMap().putAll(Map.of("enabled", true, "discardEnabled", discard));
            rspamd.enqueue(new MockResponse().setBody("{\"score\":30,\"symbols\":{}}"));
        }
    }

    private String content(String body) {
        return "From: sender@example.test\r\nTo: tony@example.com\r\nSubject: scanner safety\r\n\r\n" + body + "\r\n";
    }

    private ConnectionMock receipt(boolean binary, String body) {
        ConnectionMock connection = new ConnectionMock(new StringBuilder(content(body) + (binary ? "" : ".\r\n")));
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope().setMail("sender@example.test").addRcpt("tony@example.com"));
        return connection;
    }

    private Verb verb(boolean binary, String body) {
        return new Verb(binary ? "BDAT " + content(body).length() + " LAST" : "DATA");
    }

    private void assertDiscarded(ConnectionMock connection) throws Exception {
        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        assertTrue(envelope.isBlackholed());
        assertNull(envelope.getFile());
        assertNull(envelope.getMessageSource());
        assertFalse(envelope.getScanResults().isEmpty());
        try (var files = Files.walk(dir)) {
            assertTrue(files.noneMatch(Files::isRegularFile));
        }
    }
}
