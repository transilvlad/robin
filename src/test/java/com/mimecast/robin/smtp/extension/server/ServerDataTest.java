package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class ServerDataTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void processAscii() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r\n");
        stringBuilder.append("From: <tony@example.com>\r\n");
        stringBuilder.append("To: <pepper@example.com>\r\n");
        stringBuilder.append("Subject: Lost in space\r\n");
        stringBuilder.append("\r\n");
        stringBuilder.append("Rescue me!\r\n");
        stringBuilder.append(".\r\n\r\n\r\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (5 + 4), data.getBytesReceived());
    }

    @Test
    void processAsciiLF() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\n");
        stringBuilder.append("From: <tony@example.com>\n");
        stringBuilder.append("To: <pepper@example.com>\n");
        stringBuilder.append("Subject: Lost in space\n");
        stringBuilder.append("\n");
        stringBuilder.append("Rescue me!\n");
        stringBuilder.append(".\n\n\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (3 + 2), data.getBytesReceived());
    }

    @Test
    void processAsciiCR() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r");
        stringBuilder.append("From: <tony@example.com>\r");
        stringBuilder.append("To: <pepper@example.com>\r");
        stringBuilder.append("Subject: Lost in space\r");
        stringBuilder.append("\r");
        stringBuilder.append("Rescue me!\r");
        stringBuilder.append(".\r\r\r");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (3 + 2), data.getBytesReceived());
    }

    @Test
    void processAsciiReturns451WhenDiskSafetyFails() throws IOException {
        Object originalDiskSafety = Config.getServer().getQueue().getMap().get("diskSafety");
        Config.getServer().getQueue().getMap().put("diskSafety", Map.of(
                "enabled", true,
                "minUsableBytes", Long.MAX_VALUE
        ));
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("MIME-Version: 1.0\r\n");
            stringBuilder.append("From: <tony@example.com>\r\n");
            stringBuilder.append("To: <pepper@example.com>\r\n");
            stringBuilder.append("Subject: Lost in space\r\n");
            stringBuilder.append("\r\n");
            stringBuilder.append("Rescue me!\r\n");
            stringBuilder.append(".\r\n");

            ConnectionMock connection = new ConnectionMock(stringBuilder);
            connection.setSocket(new Socket());
            connection.getSession().addEnvelope(new MessageEnvelope());
            connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

            boolean process = new ServerData().process(connection, new Verb("DATA"));

            assertFalse(process);
            connection.parseLines();
            assertTrue(connection.getLine(1).startsWith("451 4.3.2 Internal server error"));
        } finally {
            if (originalDiskSafety == null) {
                Config.getServer().getQueue().getMap().remove("diskSafety");
            } else {
                Config.getServer().getQueue().getMap().put("diskSafety", originalDiskSafety);
            }
        }
    }

    @Test
    void processBinary() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r\n");
        stringBuilder.append("From: <tony@example.com>\r\n");
        stringBuilder.append("To: <pepper@example.com>\r\n");
        stringBuilder.append("Subject: Lost in space\r\n");
        stringBuilder.append("\r\n");
        stringBuilder.append("Rescue me!\r\n\r\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("BDAT 109 LAST");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertTrue(connection.getLine(1).startsWith("250 2.0.0 Chunk OK"), "startsWith(\"250 2.0.0 Chunk OK\")");
        assertEquals(stringBuilder.toString().length() - 2, data.getBytesReceived());
    }
}
