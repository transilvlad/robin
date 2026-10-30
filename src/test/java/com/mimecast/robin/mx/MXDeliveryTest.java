package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.SimpleDnsRecord;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.security.SecureMxRecord;
import com.mimecast.robin.smtp.security.SecurityPolicy;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MXDeliveryTest {
    @Test
    void dnsOutageKeepsDurableDomainAndReportsRetryableFailure() {
        assertRoutingFailure(false, "451");
    }

    @Test
    void nullMxOrNxdomainReportsPermanentFailure() {
        assertRoutingFailure(true, "550");
    }

    private void assertRoutingFailure(boolean permanent, String code) {
        Session queued = queued();
        MXResolver resolver = new MXResolver() {
            @Override
            public List<SecureMxRecord> resolveDeliveryMx(String domain) {
                throw new MailRoutingException("synthetic DNS failure", permanent);
            }
        };
        new MXDelivery(resolver, attempt -> fail("Must not deliver after DNS failure")).deliver(queued);
        assertEquals(code, queued.getSessionTransactionList().getErrors().getFirst().getResponseCode());
        assertEquals(List.of("destination.invalid"), queued.getMx());
        assertEquals(1, queued.getEnvelopes().size());
    }

    @Test
    void deliveryUsesFreshTransportPerMxAndDoesNotFailOverAfterMail() {
        AtomicInteger attempts = new AtomicInteger();
        MXResolver resolver = new MXResolver() {
            @Override
            public List<SecureMxRecord> resolveDeliveryMx(String domain) {
                return List.of(
                        new SecureMxRecord(new SimpleDnsRecord("192.0.2.1", 10), SecurityPolicy.mtaSts("mx1.test", "enforce")),
                        new SecureMxRecord(new SimpleDnsRecord("192.0.2.2", 20), SecurityPolicy.opportunistic("mx2.test")));
            }
        };
        Session queued = queued().setAuth(true).setPassword("untrusted").setStartTls(true);
        new MXDelivery(resolver, attempt -> {
            attempts.incrementAndGet();
            assertFalse(attempt.isAuth());
            assertFalse(attempt.isStartTls());
            assertEquals("", attempt.getPassword());
            assertTrue(attempt.isTls());
            assertEquals("mx1.test", attempt.getSecurityPolicy().getMxHostname());
            EnvelopeTransactionList envelope = new EnvelopeTransactionList();
            envelope.addTransaction("MAIL", "250 OK");
            attempt.getSessionTransactionList().addEnvelope(envelope);
        }).deliver(queued);
        assertEquals(1, attempts.get());
        assertEquals(1, queued.getSessionTransactionList().getEnvelopes().size());
        assertEquals(List.of("destination.invalid"), queued.getMx());
    }

    @Test
    void mtaStsTestingIsNotMandatoryTls() {
        assertFalse(SecurityPolicy.mtaSts("mx.test", "testing").isTlsMandatory());
        assertFalse(SecurityPolicy.mtaSts("mx.test", "none").isTlsMandatory());
        assertTrue(SecurityPolicy.mtaSts("mx.test", "enforce").isTlsMandatory());
    }

    private Session queued() {
        return new Session().setMx(List.of("destination.invalid")).addEnvelope(
                new MessageEnvelope().setMail("sender@example.test").setRcpt("recipient@destination.invalid"));
    }
}
