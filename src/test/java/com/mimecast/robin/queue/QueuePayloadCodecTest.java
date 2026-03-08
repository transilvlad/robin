package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.security.SecurityPolicy;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuePayloadCodecTest {

    @Test
    void relaySessionRoundTripPreservesDeliveryState() {
        Session session = new Session()
                .setDirection(EmailDirection.OUTBOUND)
                .setUID("session-1")
                .setMx(List.of("mx1.example.com", "mx2.example.com"))
                .setRetry(2)
                .setDelay(3)
                .setTimeout(4000)
                .setExtendedTimeout(5000)
                .setConnectTimeout(6000)
                .setBind("192.0.2.10")
                .setRdns("rdns.example.com")
                .setAddr("192.0.2.11")
                .setFriendRdns("client.example.com")
                .setFriendAddr("192.0.2.20")
                .setFriendInRbl(true)
                .setFriendRbl("zen.spamhaus.org")
                .setBlackholed(true)
                .setPort(2525)
                .setHelo("helo.example.com")
                .setLhlo("lhlo.example.com")
                .setEhlo("ehlo.example.com")
                .setEhloSize(12345L)
                .setEhloTls(true)
                .setSmtpUtf8(true)
                .setEhlo8bit(true)
                .setEhloBinary(true)
                .setEhloBdat(true)
                .setEhloLog("LHLO")
                .setEhloAuth(List.of("PLAIN", "LOGIN"))
                .setTls(true)
                .setStartTls(true)
                .setSecurityPolicy(SecurityPolicy.opportunistic("mx1.example.com"))
                .setSecurePort(true)
                .setAuthBeforeTls(true)
                .setAuth(true)
                .setAuthLoginCombined(true)
                .setAuthLoginRetry(true)
                .setUsername("user")
                .setPassword("pass")
                .setBehaviour(List.of("EHLO", "MAIL"));
        session.putMagic("customKey", "customValue");
        session.saveResults("query", List.of(Map.of("answer", "42")));

        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .setRcpts(new ArrayList<>(List.of("recipient@example.com")))
                .setFile("/tmp/queue-test.eml")
                .setBytes("body".getBytes(StandardCharsets.UTF_8))
                .setChunkSize(4096)
                .setPrependHeaders(true);
        envelope.addHeader("X-Test", "1");
        session.addEnvelope(envelope);

        RelaySession relaySession = new RelaySession(session)
                .setProtocol("lmtp")
                .setMailbox("Inbox")
                .setPoolKey("primary");
        relaySession.bumpRetryCount();

        RelaySession restored = QueuePayloadCodec.deserialize(QueuePayloadCodec.serialize(relaySession));

        assertEquals(relaySession.getUID(), restored.getUID());
        assertEquals("lmtp", restored.getProtocol());
        assertEquals("Inbox", restored.getMailbox());
        assertEquals("primary", restored.getPoolKey());
        assertEquals(1, restored.getRetryCount());
        assertEquals(relaySession.getMaxRetryCount(), restored.getMaxRetryCount());
        assertTrue(restored.getLastRetryTime() > 0);

        assertNotNull(restored.getSession());
        assertEquals(EmailDirection.OUTBOUND, restored.getSession().getDirection());
        assertEquals("session-1", restored.getSession().getUID());
        assertEquals(List.of("mx1.example.com", "mx2.example.com"), restored.getSession().getMx());
        assertEquals(2525, restored.getSession().getPort());
        assertEquals("192.0.2.10", restored.getSession().getBind());
        assertEquals("LHLO", restored.getSession().getEhloLog());
        assertEquals(List.of("PLAIN", "LOGIN"), restored.getSession().getEhloAuth());
        assertTrue(restored.getSession().isTls());
        assertTrue(restored.getSession().isAuth());
        assertEquals(SecurityPolicy.PolicyType.OPPORTUNISTIC, restored.getSession().getSecurityPolicy().getType());
        assertEquals("mx1.example.com", restored.getSession().getSecurityPolicy().getMxHostname());
        assertEquals("customValue", restored.getSession().getMagic("customKey"));
        assertEquals("42", ((Map<?, ?>) restored.getSession().getSavedResults().get("query").getFirst()).get("answer"));

        MessageEnvelope restoredEnvelope = restored.getSession().getEnvelopes().getFirst();
        assertEquals("sender@example.com", restoredEnvelope.getMail());
        assertEquals(List.of("recipient@example.com"), restoredEnvelope.getRcpts());
        assertEquals("/tmp/queue-test.eml", restoredEnvelope.getFile());
        assertEquals("1", restoredEnvelope.getHeaders().get("X-Test"));
        assertTrue(restoredEnvelope.isPrependHeaders());
        assertEquals(4096, restoredEnvelope.getChunkSize());
    }
}
