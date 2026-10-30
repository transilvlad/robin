package com.mimecast.robin.queue.bounce;

import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BounceMessageGeneratorTest {
    @Test
    void failureNotificationTargetsOriginalReversePathAndUsesNullSender() throws Exception {
        RelaySession original = original("sender@example.test");
        RelaySession bounce = new BounceMessageGenerator(original, "missing@destination.test").toRelaySession();

        assertNotNull(bounce);
        assertEquals("mx", bounce.getProtocol());
        assertEquals(List.of("example.test"), bounce.getSession().getMx());
        MessageEnvelope notification = bounce.getSession().getEnvelopes().getFirst();
        assertEquals("", notification.getMail());
        assertEquals(List.of("sender@example.test"), notification.getRcpts());
        String message = new String(notification.readMessageBytes(), StandardCharsets.UTF_8);
        assertTrue(message.contains("To: sender@example.test"));
        assertTrue(message.contains("Final-Recipient: RFC822; missing@destination.test"));
        assertTrue(message.contains("Status: 5.1.1"));
        assertTrue(message.contains("Auto-Submitted: auto-replied"));
        assertTrue(message.contains("multipart/report"));
        assertTrue(message.contains("report-type=delivery-status"));
    }

    @Test
    void nullReversePathSuppressesBounceOnBounce() {
        assertNull(new BounceMessageGenerator(original(""), "missing@destination.test").toRelaySession());
        assertNull(new BounceMessageGenerator(original("<>"), "missing@destination.test").toRelaySession());
        assertNull(new BounceMessageGenerator(original(null), "missing@destination.test").toRelaySession());
    }

    @Test
    void transportFailuresWithoutEnvelopeTransactionsProduceUsefulStatus() {
        RelaySession original = new RelaySession(new Session().addEnvelope(
                new MessageEnvelope().setMail("sender@example.test").setRcpt("missing@destination.test")));
        original.getSession().getSessionTransactionList().addTransaction("DNS", "451 4.4.3 DNS unavailable", true);
        String report = new BounceGenerator(original).generateDeliveryStatus("missing@destination.test");
        assertTrue(report.contains("Status: 5.4.7"));
        assertTrue(report.contains("451 4.4.3 DNS unavailable"));
    }

    private RelaySession original(String reversePath) {
        Session session = new Session().addEnvelope(
                new MessageEnvelope().setMail(reversePath).setRcpt("missing@destination.test"));
        session.getSessionTransactionList().addTransaction("DNS", "550 5.1.1 Unknown recipient", true);
        return new RelaySession(session);
    }
}
