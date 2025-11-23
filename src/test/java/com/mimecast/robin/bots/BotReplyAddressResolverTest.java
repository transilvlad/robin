package com.mimecast.robin.bots;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotReplyAddressResolverTest {
    @ParameterizedTest
    @DisplayName("Sieve address permutations")
    @CsvSource({
        // Only user+domain
        "robot+user+domain.com@botdomain.com,user@domain.com",
        // Token + user+domain
        "robot+token+user+domain.com@botdomain.com,user@domain.com",
        // Only token
        "robot+token@botdomain.com,,",
        // Only robot
        "robot@botdomain.com,,",
        // Token + user (no domain)
        "robot+token+user@botdomain.com,,",
        // User only (no domain)
        "robot+user@botdomain.com,,",
        // Token + user+domain with subdomain
        "robot+token+user+sub.domain.com@botdomain.com,user@sub.domain.com",
        // User+domain with subdomain
        "robot+user+sub.domain.com@botdomain.com,user@sub.domain.com",
        // Token with special chars
        "robot+tok-en_123+user+domain.com@botdomain.com,user@domain.com",
        // User with special chars
        "robot+user.name+domain.com@botdomain.com,user.name@domain.com",
        // Token + user+domain with numbers
        "robot+1234+user+domain.com@botdomain.com,user@domain.com",
        // Token + user+domain with dashes
        "robot+tok-en+user+do-main.com@botdomain.com,user@do-main.com"
    })
    void testSieveReplyAddressParsing(String botAddress, String expectedReply) {
        Session session = new Session();
        Connection connection = new Connection(session);
        String actual = BotReplyAddressResolver.resolveReplyAddress(connection, botAddress);
        assertEquals(expectedReply, actual);
    }

    @ParameterizedTest
    @DisplayName("Envelope fallback: Reply-To and From headers")
    @CsvSource({
        "reply@example.com,from@example.com,reply@example.com",
        " ,from@example.com,from@example.com",
        " , ,"
    })
    void testEnvelopeFallback(String replyTo, String from, String expected) {
        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("mailfrom@example.com");
        if (replyTo != null && !replyTo.isEmpty()) envelope.addHeader("X-Parsed-Reply-To", replyTo);
        if (from != null && !from.isEmpty()) envelope.addHeader("X-Parsed-From", from);
        session.addEnvelope(envelope);
        Connection connection = new Connection(session);
        String actual = BotReplyAddressResolver.resolveReplyAddress(connection, "robot@botdomain.com");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Envelope fallback: MAIL FROM")
    void testEnvelopeMailFromFallback() {
        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("mailfrom@example.com");
        session.addEnvelope(envelope);
        Connection connection = new Connection(session);
        String actual = BotReplyAddressResolver.resolveReplyAddress(connection, "robot@botdomain.com");
        assertEquals("mailfrom@example.com", actual);
    }

    @ParameterizedTest
    @DisplayName("Null and empty bot address")
    @NullAndEmptySource
    void testNullAndEmptyBotAddress(String botAddress) {
        Session session = new Session();
        Connection connection = new Connection(session);
        String actual = BotReplyAddressResolver.resolveReplyAddress(connection, botAddress);
        assertNull(actual);
    }
}

