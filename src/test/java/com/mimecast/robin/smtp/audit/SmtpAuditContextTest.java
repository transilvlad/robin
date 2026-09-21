package com.mimecast.robin.smtp.audit;

import com.mimecast.robin.metrics.MetricsRegistry;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import com.mimecast.robin.smtp.session.Session;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class SmtpAuditContextTest {

    @AfterEach
    void tearDown() {
        MetricsRegistry.register(null, null);
    }

    @Test
    void recordMessageAndConnection_incrementBoundedOutcomeMetrics() {
        PrometheusMeterRegistry testRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistry.register(testRegistry, null);

        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .addRcpt("first@example.net");
        SmtpAuditContext context = new SmtpAuditContext("smtp");

        context.recordResponse("250 accepted".getBytes(StandardCharsets.US_ASCII));
        context.recordMessage(session, envelope, "data", "accepted", true, 100, 5);
        context.setTerminationReason("quit");
        context.recordConnection(session);

        Counter messageCounter = testRegistry.find("robin.email.message.outcome")
                .tag("outcome", "accepted")
                .tag("protocol", "data")
                .counter();
        assertNotNull(messageCounter, "Message outcome metric should be recorded");
        assertEquals(1.0, messageCounter.count(), 0.001);

        Counter connectionCounter = testRegistry.find("robin.email.connection.outcome")
                .tag("listener", "smtp")
                .tag("termination_reason", "quit")
                .counter();
        assertNotNull(connectionCounter, "Connection outcome metric should be recorded");
        assertEquals(1.0, connectionCounter.count(), 0.001);
    }

    @Test
    void recordMessage_tracksAcceptedAndRejectedOutcomesWithoutAddresses() {
        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .addRcpt("first@example.net")
                .addRcpt("second@example.net");
        SmtpAuditContext context = new SmtpAuditContext("smtp");

        context.recordResponse("250 2.0.0 accepted".getBytes(StandardCharsets.US_ASCII));
        context.recordMessage(session, envelope, "data", "accepted", true, 123, 4);
        context.recordResponse("451 4.3.2 deferred".getBytes(StandardCharsets.US_ASCII));
        context.recordMessage(session, envelope, "data", "accepted", false, 45, 2);

        assertEquals(1, context.getAcceptedMessages());
        assertEquals(1, context.getRejectedMessages());
        assertEquals(168, context.getMessageBytes());
        assertEquals(451, context.getLastSmtpStatus());
    }

    @Test
    void recordResponse_ignoresNonStatusPayload() {
        SmtpAuditContext context = new SmtpAuditContext("smtp");

        context.recordResponse("not an SMTP response".getBytes(StandardCharsets.US_ASCII));

        assertEquals(0, context.getLastSmtpStatus());
    }

    @Test
    void setException_recordsBoundedTerminationReason() {
        SmtpAuditContext context = new SmtpAuditContext("submission");

        context.recordCommand();
        context.setException(new IllegalStateException("failure"));

        assertEquals(1, context.getCommandCount());
        assertEquals("exception", context.getTerminationReason());
    }

    @Test
    void auditRecords_areStructuredAndExcludeAddressLocalParts() {
        Logger logger = (Logger) LogManager.getLogger("com.mimecast.robin.audit");
        CapturingAppender appender = new CapturingAppender();
        Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            Session session = new Session();
            session.setFriendAddr("192.0.2.1");
            session.setFriendRdns("mail.example.net");
            MessageEnvelope envelope = new MessageEnvelope()
                    .setMail("private-sender@example.com")
                    .addRcpt("private-recipient@example.net");
            session.addEnvelope(envelope);
            SmtpAuditContext context = new SmtpAuditContext("smtp");

            context.recordResponse("250 accepted".getBytes(StandardCharsets.US_ASCII));
            context.recordMessage(session, envelope, "data", "accepted", true, 100, 5);
            context.setTerminationReason("quit");
            context.recordConnection(session);
            context.recordConnection(session);

            assertEquals(2, appender.messages().size());
            String messageRecord = appender.messages().get(0);
            assertTrue(messageRecord.startsWith("event=message_outcome "));
            assertTrue(messageRecord.contains("outcome=accepted"));
            assertTrue(messageRecord.contains("sender_domain=example.com"));
            assertTrue(messageRecord.contains("recipient_domains=example.net"));
            assertFalse(messageRecord.contains("private-sender"));
            assertFalse(messageRecord.contains("private-recipient"));

            String connectionRecord = appender.messages().get(1);
            assertTrue(connectionRecord.startsWith("event=connection_outcome "));
            assertTrue(connectionRecord.contains("remote_ip=192.0.2.1"));
            assertTrue(connectionRecord.contains("termination_reason=quit"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private CapturingAppender() {
            super("smtp-audit-test", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private List<String> messages() {
            return messages;
        }
    }
}
