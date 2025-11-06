package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ServerAuthTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void getAdvert() {
        ServerAuth auth = new ServerAuth();
        assertEquals("AUTH PLAIN LOGIN", auth.getAdvert());
    }

    @Test
    void processAuthNone() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);

        connection.parseLines();
        assertEquals(SmtpResponses.UNRECOGNIZED_AUTH_504 + "\r\n", connection.getLine(1));
    }

    @Test
    void processAuthUnknown() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH DIGEST-MD5");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);

        connection.parseLines();
        assertTrue(connection.getLine(1).startsWith(SmtpResponses.UNKNOWN_MAILBOX_550.replace("[%s]", "")));
    }

    @Test
    void processAuthPlainTrue() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("dG9ueUBleGFtcGxlLmNvbQB0b255QGV4YW1wbGUuY29tAGdpdmVIZXJUaGVSaW5n\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH PLAIN");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertTrue(process);
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_PAYLOAD_334 + "\r\n", connection.getLine(1));
        assertEquals(SmtpResponses.AUTH_SUCCESS_235 + "\r\n", connection.getLine(2));
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());
    }

    @Test
    void processAuthPlainFalse() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("AHVsdHJvbkBleGFtcGxlLmNvbQBzYXZlVGhlSHVtYW5z\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH PLAIN");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);
        assertFalse(connection.getSession().isAuth());
        assertEquals("ultron@example.com", connection.getSession().getUsername());
        assertEquals("saveTheHumans", connection.getSession().getPassword());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_PAYLOAD_334 + "\r\n", connection.getLine(1));
        assertEquals(SmtpResponses.AUTH_FAILED_535 + "\r\n", connection.getLine(2));
    }

    @Test
    void processAuthPlainOneStep() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH PLAIN AHVsdHJvbkBleGFtcGxlLmNvbQBzYXZlVGhlSHVtYW5z");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);
        assertFalse(connection.getSession().isAuth());

        connection.parseLines();
        assertTrue(connection.getLine(1).startsWith(SmtpResponses.UNKNOWN_MAILBOX_550.replace("[%s]", "")));
    }

    @Test
    void processAuthPlainExtension() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("TUFJTA==\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setSecurePort(true);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH PLAIN");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);
        assertFalse(connection.getSession().isAuth());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_PAYLOAD_334 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith(SmtpResponses.UNKNOWN_MAILBOX_550.replace("[%s]", "")));
    }

    @Test
    void processAuthLoginOneStep() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Z2l2ZUhlclRoZVJpbmc=\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setDirection(EmailDirection.OUTBOUND);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH LOGIN dG9ueUBleGFtcGxlLmNvbQ==");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertTrue(process);
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_PASSWORD_334 + "\r\n", connection.getLine(1));
        assertEquals(SmtpResponses.AUTH_SUCCESS_235 + "\r\n", connection.getLine(2));
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());
    }

    @Test
    void processAuthLoginTwoStep() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("dG9ueUBleGFtcGxlLmNvbQ==\r\n");
        stringBuilder.append("Z2l2ZUhlclRoZVJpbmc=\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setDirection(EmailDirection.OUTBOUND);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH LOGIN");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertTrue(process);
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_USERNAME_334 + "\r\n", connection.getLine(1));
        assertEquals(SmtpResponses.AUTH_PASSWORD_334 + "\r\n", connection.getLine(2));
        assertEquals(SmtpResponses.AUTH_SUCCESS_235 + "\r\n", connection.getLine(3));
        assertTrue(connection.getSession().isAuth());
        assertEquals("tony@example.com", connection.getSession().getUsername());
        assertEquals("giveHerTheRing", connection.getSession().getPassword());
    }

    @Test
    void processAuthLoginExtension() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("TUFJTA==\r\n");
        stringBuilder.append("UkNQVA==\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setDirection(EmailDirection.OUTBOUND);
        connection.getSession().setStartTls(true);

        Verb verb = new Verb("AUTH LOGIN");

        ServerAuth auth = new ServerAuth();
        boolean process = auth.process(connection, verb);

        assertFalse(process);
        assertFalse(connection.getSession().isAuth());
        assertEquals("", connection.getSession().getUsername());
        assertEquals("", connection.getSession().getPassword());

        connection.parseLines();
        assertEquals(SmtpResponses.AUTH_USERNAME_334 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith(SmtpResponses.UNKNOWN_MAILBOX_550.replace("[%s]", "")));
    }
}
