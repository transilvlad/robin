package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.EmailDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.naming.ConfigurationException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(value = "storage-config", mode = ResourceAccessMode.READ_WRITE)
class SpamStorageProcessorTraceHeadersTest {
    private static final byte[] MESSAGE = ("Authentication-Results: elsewhere; none\r\n"
            + "X-Spam-Status: Yes, score=9\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path directory;

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @AfterEach
    void after() {
        Config.getServer().getMap().remove("traceHeaders");
        Config.getServer().getRspamd().getMap().put("enabled", false);
    }

    private ConnectionMock connection(EmailDirection direction) {
        Config.getServer().getRspamd().getMap().put("enabled", false);
        ConnectionMock connection = new ConnectionMock(new StringBuilder());
        connection.getSession().setDirection(direction);
        connection.getSession().setFriendAddr("10.0.0.5");
        MessageEnvelope envelope = new MessageEnvelope().setBytes(MESSAGE).setFile(directory.resolve("in.eml").toString());
        envelope.setMail("alice@sender.example");
        connection.getSession().addEnvelope(envelope);
        return connection;
    }

    private static String process(ConnectionMock connection) throws Exception {
        assertTrue(new SpamStorageProcessor(host -> {
            throw new UnknownHostException(host);
        }).process(connection, null));
        return new String(connection.getSession().getEnvelopes().getLast().readMessageBytes(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void unscannedInboundMailGetsAuthenticationResultsAndKeepsItsSpamHeaders() throws Exception {
        String text = process(connection(EmailDirection.INBOUND));

        assertTrue(text.startsWith("Authentication-Results: " + Config.getServer().getHostname() + "; none\r\n"
                + "Authentication-Results: elsewhere; none\r\nX-Spam-Status: Yes, score=9\r\n"), text);
    }

    @Test
    void ourAuthservIdIsRemovedFromIncomingResults() throws Exception {
        ConnectionMock connection = connection(EmailDirection.INBOUND);
        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        envelope.setBytes(("Authentication-Results: " + Config.getServer().getHostname()
                + ";\r\n\tspf=pass smtp.mailfrom=forged@example.com\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.US_ASCII));

        String text = process(connection);

        assertFalse(text.contains("forged@example.com"), text);
    }

    @Test
    void outboundMailIsLeftAsSubmitted() throws Exception {
        ConnectionMock connection = connection(EmailDirection.OUTBOUND);

        process(connection);

        assertArrayEquals(MESSAGE, connection.getSession().getEnvelopes().getLast().readMessageBytes());
    }

    @Test
    void journalArchivesCanTurnTheHeadersOff() throws Exception {
        Config.getServer().getMap().put("traceHeaders", Map.of("authenticationResults", false, "spam", false));
        ConnectionMock connection = connection(EmailDirection.INBOUND);

        process(connection);

        assertArrayEquals(MESSAGE, connection.getSession().getEnvelopes().getLast().readMessageBytes());
    }
}
