package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRcptTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void processWithRcpt() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        envelope.setRcpt("pepper@example.com");
        envelope.setFile("src/test/resources/mime/lipsum.eml");
        connection.getSession().addEnvelope(envelope);

        new ClientMail().process(connection); // required before RCPT

        ClientRcpt rcpt = new ClientRcpt();
        boolean process = rcpt.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("MAIL FROM:<tony@example.com> SIZE=2744\r\n", connection.getLine(1));
        assertEquals("RCPT TO:<pepper@example.com>\r\n", connection.getLine(2));
    }

    @Test
    void processWithRcpts() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        envelope.setRcpts(Collections.singletonList("pepper@example.com"));
        envelope.setFile("src/test/resources/mime/lipsum.eml");
        connection.getSession().addEnvelope(envelope);

        new ClientMail().process(connection); // required before RCPT

        ClientRcpt rcpt = new ClientRcpt();
        boolean process = rcpt.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("MAIL FROM:<tony@example.com> SIZE=2744\r\n", connection.getLine(1));
        assertEquals("RCPT TO:<pepper@example.com>\r\n", connection.getLine(2));
    }

    @Test
    void recipientRejectionAfterAcceptanceStillRecordsFailure() throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder(
                "250 Sender OK\r\n250 First recipient OK\r\n451 Second recipient unavailable\r\n"));
        connection.getSession().addEnvelope(new MessageEnvelope().setMail("sender@example.test")
                .setRcpts(List.of("first@example.test", "second@example.test")));
        new ClientMail().process(connection);

        assertTrue(new ClientRcpt().process(connection));
        assertEquals(List.of("second@example.test"), connection.getSession()
                .getSessionTransactionList().getEnvelopes().getFirst().getFailedRecipients());
    }
}
