package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEhloTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    private static String ehlo(EmailDirection direction, boolean securePort, boolean tls) throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder());
        connection.getSession().setFriendRdns("example.com");
        connection.getSession().setFriendAddr("127.0.0.1");
        connection.getSession().setDirection(direction);
        connection.getSession().setSecurePort(securePort);
        connection.getSession().setStartTls(tls);

        ServerEhlo ehlo = new ServerEhlo();
        assertTrue(ehlo.process(connection, new Verb("EHLO example.com")));
        assertEquals("example.com", connection.getSession().getEhlo());
        return connection.getOutput();
    }

    @Test
    void process() throws IOException {
        // Implicit TLS port: TLS is up before EHLO.
        String out = ehlo(EmailDirection.INBOUND, true, true);

        assertTrue(out.contains("example.com"));
        assertTrue(out.contains("127.0.0.1"));
        assertTrue(out.contains("HELP"));
        assertTrue(out.contains("AUTH"));
        assertTrue(out.contains("PLAIN"));
        assertTrue(out.contains("LOGIN"));
        assertFalse(out.contains("STARTTLS"), "No STARTTLS once TLS is established");
    }

    @Test
    void submissionBeforeTlsOffersStartTlsButNotAuth() throws IOException {
        String out = ehlo(EmailDirection.OUTBOUND, false, false);

        assertTrue(out.contains("STARTTLS"));
        assertFalse(out.contains("AUTH"), "AUTH must not be offered in cleartext");
    }

    @Test
    void submissionAfterTlsOffersAuthButNotStartTls() throws IOException {
        String out = ehlo(EmailDirection.OUTBOUND, false, true);

        assertTrue(out.contains("AUTH PLAIN LOGIN"));
        assertFalse(out.contains("STARTTLS"));
    }

    @Test
    void inboundPortNeverOffersAuth() throws IOException {
        assertFalse(ehlo(EmailDirection.INBOUND, false, false).contains("AUTH"));
        assertFalse(ehlo(EmailDirection.INBOUND, false, true).contains("AUTH"));
    }
}
