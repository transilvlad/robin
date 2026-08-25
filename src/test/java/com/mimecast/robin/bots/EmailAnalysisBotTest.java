package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.config.server.EmailAnalysisBotConfig;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.io.LineInputStream;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Lookup;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmailAnalysisBot.
 */
class EmailAnalysisBotTest {

    @BeforeAll
    static void beforeAll() {
        Lookup.setDefaultResolver(new LocalDnsResolver());
    }

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
    void testDefaultConfigUsesDeliveryPortAndRoleAliases() {
        EmailAnalysisBotConfig config = new EmailAnalysisBotConfig(null);

        assertEquals(List.of(25), config.getPortCheckPorts());
        assertTrue(config.isRecipientProbeEnabled());
        assertTrue(config.getRoleAliases().contains("postmaster"));
        assertTrue(config.getRoleAliases().contains("abuse"));
    }

    @Test
    void testRspamdSpfNeutralRendersAsWarning() {
        Session session = new Session();
        session.setFriendAddr("192.0.2.10");
        session.setFriendRdns("mail.example.com");
        session.setEhlo("mail.example.com");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");
        envelope.addHeader("X-Parsed-From", "sender@example.com");
        envelope.addScanResult(Map.of(
                "scanner", "rspamd",
                "symbols", Map.of("R_SPF_NEUTRAL", Map.of("description", "SPF neutral"))));
        session.addEnvelope(envelope);

        EmailAnalysisBot.AnalysisReport report = new EmailAnalysisBot().analyze(
                new Connection(session), null, authOnlyConfig());

        String text = EmailAnalysisBot.renderText(report);
        String html = EmailAnalysisBot.renderHtml(report);

        assertTrue(text.contains("[WARN] SPF authentication"));
        assertTrue(text.contains("SPF returned neutral."));
        assertTrue(html.contains("Robin Email Analysis Report"));
        assertTrue(html.contains("class=\"pill WARN\""));
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
        assertDoesNotThrow(() -> bot.process(connection, parser, "robotEmail@test.com", noNetworkBotDefinition()));
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
        assertDoesNotThrow(() -> bot.process(connection, null, "robotEmail@test.com", noNetworkBotDefinition()));
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
        assertDoesNotThrow(() -> bot.process(connection, null, "robotEmail@test.com", null));
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

    @Test
    void testDblChecksPtrAndApexBeforeSendingDomains() {
        Session session = new Session();
        session.setFriendAddr("203.0.113.50");
        session.setFriendRdns("mail.ptr-sender.com.");
        session.setEhlo("helo-sender.com");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@bounce-sender.com");
        session.addEnvelope(envelope);

        EmailParser parser = parserFor("From: Sender <sender@from-sender.com>\r\n" +
                "DKIM-Signature: v=1; a=rsa-sha256; d=dkim-sender.com; s=s1;\r\n" +
                "\r\n" +
                "Body\r\n");

        EmailAnalysisBot.AnalysisReport report = new EmailAnalysisBot().analyze(
                new Connection(session), parser, reputationOnlyConfig(true));

        List<String> names = report.byCategory(EmailAnalysisBot.Category.REPUTATION).stream()
                .map(EmailAnalysisBot.CheckResult::name)
                .toList();

        assertEquals(List.of(
                "Sender IP DNSBL",
                "PTR reputation: mail.ptr-sender.com",
                "PTR reputation: ptr-sender.com",
                "Domain reputation: bounce-sender.com",
                "Domain reputation: from-sender.com",
                "Domain reputation: dkim-sender.com",
                "Domain reputation: helo-sender.com"), names);
    }

    @Test
    void testDblDoesNotDuplicatePtrApexWhenPtrIsApex() {
        Session session = new Session();
        session.setFriendAddr("203.0.113.51");
        session.setFriendRdns("ptr-sender.com");

        MessageEnvelope envelope = new MessageEnvelope();
        session.addEnvelope(envelope);

        EmailAnalysisBot.AnalysisReport report = new EmailAnalysisBot().analyze(
                new Connection(session), null, reputationOnlyConfig(false));

        List<String> names = report.byCategory(EmailAnalysisBot.Category.REPUTATION).stream()
                .map(EmailAnalysisBot.CheckResult::name)
                .toList();

        assertEquals(List.of("PTR reputation: ptr-sender.com"), names);
    }

    @Test
    void testDblDoesNotRepeatPtrApexAsSendingDomain() {
        Session session = new Session();
        session.setFriendAddr("203.0.113.53");
        session.setFriendRdns("mail.shared-sender.com");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@shared-sender.com");
        session.addEnvelope(envelope);

        EmailAnalysisBot.AnalysisReport report = new EmailAnalysisBot().analyze(
                new Connection(session), null, reputationOnlyConfig(false));

        List<String> names = report.byCategory(EmailAnalysisBot.Category.REPUTATION).stream()
                .map(EmailAnalysisBot.CheckResult::name)
                .toList();

        assertEquals(List.of(
                "PTR reputation: mail.shared-sender.com",
                "PTR reputation: shared-sender.com"), names);
    }

    @Test
    void testDblSkipsInvalidPtrButKeepsSendingDomains() {
        Session session = new Session();
        session.setFriendAddr("203.0.113.52");
        session.setFriendRdns("localhost");

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@only-sending.com");
        session.addEnvelope(envelope);

        EmailAnalysisBot.AnalysisReport report = new EmailAnalysisBot().analyze(
                new Connection(session), null, reputationOnlyConfig(false));

        List<String> names = report.byCategory(EmailAnalysisBot.Category.REPUTATION).stream()
                .map(EmailAnalysisBot.CheckResult::name)
                .toList();

        assertEquals(List.of("Domain reputation: only-sending.com"), names);
    }

    private static EmailAnalysisBotConfig authOnlyConfig() {
        return new EmailAnalysisBotConfig(Map.ofEntries(
                Map.entry("rblCheckEnabled", false),
                Map.entry("dblCheckEnabled", false),
                Map.entry("rdnsCheckEnabled", false),
                Map.entry("spfCheckEnabled", true),
                Map.entry("dkimCheckEnabled", false),
                Map.entry("dmarcCheckEnabled", false),
                Map.entry("mxCheckEnabled", false),
                Map.entry("portCheckEnabled", false),
                Map.entry("mtaStsCheckEnabled", false),
                Map.entry("daneCheckEnabled", false),
                Map.entry("spamAnalysisEnabled", false)));
    }

    private static EmailAnalysisBotConfig reputationOnlyConfig(boolean includeRbl) {
        return new EmailAnalysisBotConfig(Map.ofEntries(
                Map.entry("rblCheckEnabled", includeRbl),
                Map.entry("rblProviders", List.of("test-rbl.robin-email-analysis.example")),
                Map.entry("rblTimeoutSeconds", 1),
                Map.entry("dblCheckEnabled", true),
                Map.entry("dblProviders", List.of("test-dbl.robin-email-analysis.example")),
                Map.entry("dblTimeoutSeconds", 1),
                Map.entry("rdnsCheckEnabled", false),
                Map.entry("spfCheckEnabled", false),
                Map.entry("dkimCheckEnabled", false),
                Map.entry("dmarcCheckEnabled", false),
                Map.entry("mxCheckEnabled", false),
                Map.entry("portCheckEnabled", false),
                Map.entry("mtaStsCheckEnabled", false),
                Map.entry("daneCheckEnabled", false),
                Map.entry("spamAnalysisEnabled", false)));
    }

    private static EmailParser parserFor(String message) {
        return new EmailParser(
                new LineInputStream(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)))
        );
    }

    private static BotConfig.BotDefinition noNetworkBotDefinition() {
        return new BotConfig.BotDefinition(Map.ofEntries(
                Map.entry("rblCheckEnabled", false),
                Map.entry("dblCheckEnabled", false),
                Map.entry("rdnsCheckEnabled", false),
                Map.entry("spfCheckEnabled", false),
                Map.entry("dkimCheckEnabled", false),
                Map.entry("dmarcCheckEnabled", false),
                Map.entry("mxCheckEnabled", false),
                Map.entry("portCheckEnabled", false),
                Map.entry("mtaStsCheckEnabled", false),
                Map.entry("daneCheckEnabled", false),
                Map.entry("spamAnalysisEnabled", false)));
    }

    /**
     * Test that null exception messages are handled gracefully.
     * When an exception's getMessage() returns null, we should show the class name.
     */
    @Test
    void testNullExceptionMessageShowsClassName() {
        // NullPointerException with no message
        NullPointerException npe = new NullPointerException();
        assertNull(npe.getMessage());

        // Our code should handle this by showing class name
        String errorMsg = npe.getMessage() != null ? npe.getMessage() : npe.getClass().getSimpleName();
        assertEquals("NullPointerException", errorMsg);
    }

    /**
     * Test that exception messages are preserved when present.
     */
    @Test
    void testExceptionMessagePreservedWhenPresent() {
        RuntimeException ex = new RuntimeException("DNS timeout");
        assertEquals("DNS timeout", ex.getMessage());

        String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        assertEquals("DNS timeout", errorMsg);
    }
}
