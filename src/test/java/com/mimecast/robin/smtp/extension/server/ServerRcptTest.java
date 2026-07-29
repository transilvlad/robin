package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRcptTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void processWithScenario() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().addEnvelope(new MessageEnvelope());

        Verb verb = new Verb("RCPT TO: <friday-123@example.com>");

        ServerRcpt rcpt = new ServerRcpt();
        boolean process = rcpt.process(connection, verb);

        assertTrue(process);
        assertEquals("friday-123@example.com", rcpt.getAddress().getAddress());
        assertEquals("friday-123@example.com", connection.getSession().getEnvelopes().getLast().getRcpts().get(0));
    }

    @Test
    void processWithTempFailScenario() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().addEnvelope(new MessageEnvelope());

        Verb verb = new Verb("RCPT TO: <tempfail@mirobinbot.com>");

        ServerRcpt rcpt = new ServerRcpt();
        boolean process = rcpt.process(connection, verb);

        // Temp failure returns false (4xx response).
        assertFalse(process);
        // Recipient should NOT be added to envelope on failure.
        assertTrue(connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty());
    }

    @Test
    void processWithPermFailScenario() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().addEnvelope(new MessageEnvelope());

        Verb verb = new Verb("RCPT TO: <permfail-reject@mirobinbot.com>");

        ServerRcpt rcpt = new ServerRcpt();
        boolean process = rcpt.process(connection, verb);

        // Perm failure returns false (5xx response).
        assertFalse(process);
        // Recipient should NOT be added to envelope on failure.
        assertTrue(connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty());
    }
}
