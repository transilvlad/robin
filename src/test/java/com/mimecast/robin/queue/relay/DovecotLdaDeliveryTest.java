package com.mimecast.robin.queue.relay;

import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DovecotLdaDeliveryTest {

    @Test
    void success() {
        RelaySession relaySession = new RelaySession(new Session());
        relaySession.getSession().addEnvelope(new MessageEnvelope());
        relaySession.getSession().getEnvelopes().getLast().addRcpt("tony@example.com");

        new DovecotLdaDeliveryMock(relaySession, Pair.of(0, "")).send();

        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getRecipients().size());
        assertEquals(0, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients().size());
    }

    @Test
    void failure() {
        RelaySession relaySession = new RelaySession(new Session());
        relaySession.getSession().addEnvelope(new MessageEnvelope());
        relaySession.getSession().getEnvelopes().getLast().addRcpt("tony@example.com");

        new DovecotLdaDeliveryMock(relaySession, Pair.of(75, "")).send();

        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getRecipients().size());
        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients().size());
    }
}
