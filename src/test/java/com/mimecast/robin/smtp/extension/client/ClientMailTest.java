package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMailTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void process() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        envelope.setFile("src/test/resources/mime/lipsum.eml");
        connection.getSession().addEnvelope(envelope);

        ClientMail mail = new ClientMail();
        boolean process = mail.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("MAIL FROM:<tony@example.com> SIZE=2744\r\n", connection.getLine(1));
    }

    @Test
    void eightBitMessageDeclaresItsBodyType() throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder("250 OK\r\n"));
        connection.getSession().setEhlo8bit(true);
        MessageEnvelope envelope = new MessageEnvelope().setEightBitMime(true);
        envelope.setMail("tony@example.com");
        connection.getSession().addEnvelope(envelope);

        assertTrue(new ClientMail().process(connection));

        connection.parseLines();
        assertEquals("MAIL FROM:<tony@example.com> BODY=8BITMIME\r\n", connection.getLine(1));
    }

    @Test
    void eightBitMessageIsNotSentToAServerWithout8bitmime() throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder("250 OK\r\n"));
        MessageEnvelope envelope = new MessageEnvelope().setEightBitMime(true);
        envelope.setMail("tony@example.com");
        connection.getSession().addEnvelope(envelope);

        assertFalse(new ClientMail().process(connection));

        assertEquals("", connection.getOutput());
        assertEquals("554 5.6.3 Conversion required but not supported", connection.getSession()
                .getSessionTransactionList().getEnvelopes().getFirst().getTransactions("MAIL").getFirst().getResponse());
    }
}
