package com.mimecast.robin.bots;

import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.io.LineInputStream;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmailAnalysisBot.
 */
class EmailAnalysisBotTest {

    @Test
    void testEmailBotIsRegistered() {
        assertTrue(Factories.hasBot("email"));
        assertTrue(Factories.hasBot("Email")); // Case-insensitive.
        assertTrue(Factories.hasBot("EMAIL"));
    }

    @Test
    void testGetEmailBot() {
        var botOpt = Factories.getBot("email");
        assertTrue(botOpt.isPresent());

        BotProcessor bot = botOpt.get();
        assertNotNull(bot);
        assertEquals("email", bot.getName());
        assertInstanceOf(EmailAnalysisBot.class, bot);
    }

    @Test
    void testBotNameIsEmail() {
        EmailAnalysisBot bot = new EmailAnalysisBot();
        assertEquals("email", bot.getName());
    }

    @Test
    void testBotProcessWithValidSession() {
        // Create a mock session with required data
        Session session = new Session();
        session.setFriendAddr("192.168.1.100");
        session.setFriendRdns("mail.example.com");
        session.setEhlo("client.example.com");

        // Create envelope with reply address
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");
        envelope.addRcpt("robotEmail@test.com");
        envelope.addHeader("X-Parsed-From", "sender@example.com");
        session.addEnvelope(envelope);

        // Create connection
        Connection connection = new Connection(session);

        // Create a simple email for parsing
        String testEmail = "From: sender@example.com\r\n" +
                "To: robotEmail@test.com\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Test body\r\n";

        EmailParser parser = new EmailParser(
                new LineInputStream(new ByteArrayInputStream(testEmail.getBytes(StandardCharsets.UTF_8)))
        );

        // Process bot (should not throw)
        EmailAnalysisBot bot = new EmailAnalysisBot();
        assertDoesNotThrow(() -> bot.process(connection, parser, "robotEmail@test.com"));
    }

    @Test
    void testBotHandlesNullEmailParser() {
        Session session = new Session();
        session.setFriendAddr("127.0.0.1");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");
        envelope.addHeader("X-Parsed-From", "sender@example.com");
        session.addEnvelope(envelope);

        Connection connection = new Connection(session);

        EmailAnalysisBot bot = new EmailAnalysisBot();

        // Should handle null parser gracefully (bots run async after parser is closed).
        assertDoesNotThrow(() -> bot.process(connection, null, "robotEmail@test.com"));
    }

    @Test
    void testBotHandlesNoReplyAddress() {
        Session session = new Session();
        session.setFriendAddr("127.0.0.1");

        MessageEnvelope envelope = new MessageEnvelope();
        // No mail from, no headers - no way to reply.
        session.addEnvelope(envelope);

        Connection connection = new Connection(session);

        EmailAnalysisBot bot = new EmailAnalysisBot();
        // Should handle gracefully and log warning.
        assertDoesNotThrow(() -> bot.process(connection, null, "robotEmail@test.com"));
    }

    @Test
    void testMultipleBotsRegistered() {
        // Verify both session and email bots are registered
        assertTrue(Factories.hasBot("session"));
        assertTrue(Factories.hasBot("email"));

        String[] botNames = Factories.getBotNames();
        assertNotNull(botNames);
        assertTrue(botNames.length >= 2);

        // Check both are in the list
        boolean hasSession = false;
        boolean hasEmail = false;
        for (String name : botNames) {
            if ("session".equals(name)) hasSession = true;
            if ("email".equals(name)) hasEmail = true;
        }
        assertTrue(hasSession, "Session bot should be registered");
        assertTrue(hasEmail, "Email bot should be registered");
    }
}
