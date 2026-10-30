package com.mimecast.robin.mx.client;

import com.mimecast.robin.mx.MailRoutingException;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.xbill.DNS.*;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class DeliveryDnsResolutionTest {
    private Resolver previous;

    @BeforeEach
    void setUp() {
        previous = Lookup.getDefaultResolver();
        Lookup.setDefaultResolver(new DeliveryResolver());
    }

    @AfterEach
    void restore() {
        Lookup.setDefaultResolver(previous);
    }

    @Test
    void nullMxNeverFallsBackToAnAddress() {
        MailRoutingException failure = assertThrows(MailRoutingException.class,
                () -> new XBillDnsRecordClient().getDeliveryMxRecords("delivery-nullmx.test."));
        assertTrue(failure.isPermanent());
        assertTrue(failure.getMessage().contains("Null MX"));
    }

    @Test
    void nxdomainIsPermanent() {
        MailRoutingException failure = assertThrows(MailRoutingException.class,
                () -> new XBillDnsRecordClient().getDeliveryMxRecords("delivery-nxdomain.test."));
        assertTrue(failure.isPermanent());
    }

    @Test
    void servfailNeverFallsBackToAnAddress() {
        MailRoutingException failure = assertThrows(MailRoutingException.class,
                () -> new XBillDnsRecordClient().getDeliveryMxRecords("delivery-servfail.test."));
        assertFalse(failure.isPermanent());
    }

    @Test
    void noMxWithAnAddressUsesImplicitMx() {
        var records = new XBillDnsRecordClient().getDeliveryMxRecords("delivery-fallback.test.");
        assertEquals(1, records.size());
        assertEquals("delivery-fallback.test.", records.getFirst().getValue());
    }

    private static class DeliveryResolver extends LocalDnsResolver {
        @Override
        public Message send(Message query) {
            Message response = new Message(query.getHeader().getID());
            response.getHeader().setFlag(Flags.QR);
            response.getHeader().setFlag(Flags.AA);
            var question = query.getQuestion();
            response.addRecord(question, Section.QUESTION);
            String name = question.getName().toString();
            try {
                if (name.contains("nxdomain")) {
                    response.getHeader().setRcode(Rcode.NXDOMAIN);
                } else if (question.getType() == Type.MX && name.contains("servfail")) {
                    response.getHeader().setRcode(Rcode.SERVFAIL);
                } else if (question.getType() == Type.MX && name.contains("nullmx")) {
                    response.addRecord(new MXRecord(question.getName(), DClass.IN, 60, 0, Name.root), Section.ANSWER);
                } else if (question.getType() == Type.A) {
                    response.addRecord(new ARecord(question.getName(), DClass.IN, 60,
                            InetAddress.getByName("192.0.2.1")), Section.ANSWER);
                }
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError(e);
            }
            return response;
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query) {
            return CompletableFuture.completedFuture(send(query));
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query, Executor executor) {
            return sendAsync(query);
        }
    }
}
