package com.mimecast.robin.storage;

import com.google.gson.Gson;
import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.EmailDirection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class RspamdPolicyTest {
    @TempDir
    Path directory;
    private MockWebServer scanner;
    private ConnectionMock connection;
    private MessageEnvelope envelope;

    @BeforeEach
    void start() throws Exception {
        scanner = new MockWebServer();
        scanner.start();
        Files.writeString(directory.resolve("server.json5"), new Gson().toJson(Map.of("rspamd",
                Map.of("enabled", true, "host", "127.0.0.1", "port", scanner.getPort(), "discardEnabled", false))));
        Config.initServer(directory.resolve("server.json5").toString());
        connection = new ConnectionMock(new StringBuilder());
        connection.getSession().setAuth(true).setUsername("alice@example.com").setDirection(EmailDirection.OUTBOUND)
                .setEhlo("verified-helo.example");
        envelope = new MessageEnvelope().setMail("alice@example.com")
                .setRcpts(List.of("one@example.net", "two@example.net"))
                .setBytes("From: alice@example.com\r\nSubject: sample\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII))
                .setFile(directory.resolve("input.eml").toString());
        connection.getSession().addEnvelope(envelope);
    }

    @AfterEach
    void stop() throws Exception {
        if (envelope.getMessageSource() != null) envelope.getMessageSource().release();
        scanner.shutdown();
        ConfigLoader.load("src/test/resources/cfg/");
    }

    @Test
    void authoritativeActionOverridesNumericThresholdAndIncludesVerifiedSmtpContext() throws Exception {
        respond(999, "no action", Map.of());
        assertTrue(new SpamStorageProcessor().process(connection, null));
        assertFalse(envelope.isBlackholed());
        var request = scanner.takeRequest();
        assertEquals("alice@example.com", request.getHeader("User"));
        assertEquals("verified-helo.example", request.getHeader("Helo"));
        assertEquals(List.of("one@example.net", "two@example.net"), request.getHeaders().values("RCPT"));
        assertEquals("outbound", request.getHeader("MTA-Tag"));
    }

    @Test
    void softRejectDefersAndDiscardDisabledRejectsWithoutLosingMessage() throws Exception {
        respond(0, "soft reject", Map.of());
        assertFalse(new SpamStorageProcessor().process(connection, null));
        assertTrue(connection.getOutput().contains("451 4.7.1"));
        assertTrue(connection.isSmtpResponseSent());
        respond(0, "discard", Map.of());
        assertFalse(new SpamStorageProcessor().process(connection, null));
        assertFalse(envelope.isBlackholed());
        assertTrue(connection.getOutput().contains("541 5.7.1"));
    }

    @Test
    void requiredSigningMustHaveFreshRspamdOutputAndAppliesActualWireSignature() throws Exception {
        Config.getServer().getRspamd().getMap().put("requireDkimSigning", true);
        respond(0, "no action", Map.of());
        assertThrows(IOException.class, () -> new SpamStorageProcessor().process(connection, null));

        respond(0, "no action", Map.of("milter", Map.of("add_headers",
                Map.of("DKIM-Signature", Map.of("value", "v=1; d=example.com; b=synthetic", "order", 0)))));
        assertTrue(new SpamStorageProcessor().process(connection, null));
        assertTrue(new String(envelope.readMessageBytes(), StandardCharsets.US_ASCII).startsWith("DKIM-Signature:"));
    }

    @Test
    void requiredSigningCannotBeBypassedByDisablingScannerAndUnauthenticatedUserIsNotForwarded() throws Exception {
        Config.getServer().getRspamd().getMap().put("requireDkimSigning", true);
        Config.getServer().getRspamd().getMap().put("enabled", false);
        assertThrows(IOException.class, () -> new SpamStorageProcessor().process(connection, null));
        connection.getSession().setAuth(false);
        Config.getServer().getRspamd().getMap().put("enabled", true);
        respond(0, "no action", Map.of());
        assertTrue(new SpamStorageProcessor().process(connection, null));
        assertNull(scanner.takeRequest().getHeader("User"));
    }

    private void respond(double score, String action, Map<String, Object> extra) {
        Map<String, Object> response = new HashMap<>(extra);
        response.put("score", score);
        response.put("action", action);
        scanner.enqueue(new MockResponse().setBody(new Gson().toJson(response)));
    }
}
