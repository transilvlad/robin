package com.mimecast.robin.queue.relay;

import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DovecotLdaClientTest {

    @Test
    void success() {
        RelaySession relaySession = new RelaySession(new Session());
        relaySession.getSession().addEnvelope(new MessageEnvelope());
        relaySession.getSession().getEnvelopes().getLast().addRcpt("tony@example.com");

        new DovecotLdaClientMock(relaySession, Pair.of(0, "")).send();

        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getRecipients().size());
        assertEquals(0, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients().size());
    }

    @Test
    void failure() {
        RelaySession relaySession = new RelaySession(new Session());
        relaySession.getSession().addEnvelope(new MessageEnvelope());
        relaySession.getSession().getEnvelopes().getLast().addRcpt("tony@example.com");

        new DovecotLdaClientMock(relaySession, Pair.of(75, "")).send();

        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getRecipients().size());
        assertEquals(1, relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients().size());
    }

    @Test
    void usesInMemoryMessageSourceWithoutMaterializingFile() throws IOException {
        RelaySession relaySession = new RelaySession(new Session());
        byte[] bytes = "Subject: test\r\n\r\nbody".getBytes();
        MessageEnvelope envelope = new MessageEnvelope()
                .setBytes(bytes);
        envelope.addRcpt("tony@example.com");
        relaySession.getSession().addEnvelope(envelope);

        DovecotLdaClient client = new DovecotLdaClientMock(relaySession, Pair.of(0, "")) {
            @Override
            protected Pair<Integer, String> callDovecotLda(String recipient) throws IOException, InterruptedException {
                MessageEnvelope current = relaySession.getSession().getEnvelopes().getFirst();
                assertEquals("Subject: test\r\n\r\nbody", new String(current.readMessageBytes()));
                return super.callDovecotLda(recipient);
            }
        };

        client.send();

        assertEquals(bytes.length, relaySession.getSession().getEnvelopes().getFirst().readMessageBytes().length);
        assertFalse(relaySession.getSession().getEnvelopes().getFirst().hasMaterializedFile());
    }
}
