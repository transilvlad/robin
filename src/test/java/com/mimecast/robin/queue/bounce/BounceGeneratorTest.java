package com.mimecast.robin.queue.bounce;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BounceGeneratorTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/");
    }

    private static Session session = new Session();
    private static final RelaySession relaySession = new RelaySession(session);

    static {
        EnvelopeTransactionList etl = new EnvelopeTransactionList();
        etl.addTransaction("RCPT", "550 5.0.0 Mailbox not found", true);

        session.getSessionTransactionList().addEnvelope(etl);
        session.setFriendRdns("stark.example.com")
                .setFriendAddr("10.20.0.1")
                .addEnvelope(new MessageEnvelope().setMail("peppr@example.com").addRcpt("tony@example.com"));
    }

    @Test
    void testGeneratePlainText() {
        BounceGenerator bounceGenerator = new BounceGenerator(relaySession);
        String result = bounceGenerator.generatePlainText("tony@example.com");


        assertTrue(result.contains("The original message was received at " + relaySession.getSession().getDate()));
        assertTrue(result.contains("from stark.example.com [10.20.0.1]"));

        assertTrue(result.contains("   ----- The following addresses had permanent fatal errors -----"));
        assertTrue(result.contains("From: <peppr@example.com>"));
        assertTrue(result.contains("To: <tony@example.com>"));
        assertTrue(result.contains("    (reason: 550 5.0.0 Mailbox not found)"));
    }

    @Test
    void testGenerateDeliveryStatus() {
        BounceGenerator bounceGenerator = new BounceGenerator(relaySession);
        String result = bounceGenerator.generateDeliveryStatus("tony@example.com");

        assertTrue(result.contains("Reporting-MTA: dns; example.com"));
        assertTrue(result.contains("Received-From-MTA: DNS; stark.example.com"));
        assertTrue(result.contains("Arrival-Date: " + session.getDate()));

        assertTrue(result.contains("Final-Recipient: RFC822; tony@example.com"));
        assertTrue(result.contains("Action: failed"));
        assertTrue(result.contains("Status: 5.0.0"));
        assertTrue(result.contains("Remote-MTA: DNS; [10.20.0.1]"));
        assertTrue(result.contains("Diagnostic-Code: SMTP; 550 5.0.0 Mailbox not found"));
        assertTrue(result.contains("Last-Attempt-Date: " + relaySession.getLastRetryDate()));
    }
}
