package com.mimecast.robin.mx;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionRoutingTest {

    @Test
    void getSessions_singleLegacyRecipient_routesWithoutDuplicateRcpt() {
        Session session = new Session();
        session.addEnvelope(new MessageEnvelope()
                .setMail("robotEmail@example.test")
                .setRcpt("sender@example.test"));

        List<Session> routedSessions = new SessionRouting(session, new FixedMxResolver()).getSessions();

        assertEquals(1, routedSessions.size());
        assertEquals(List.of("sender@example.test"),
                routedSessions.getFirst().getEnvelopes().getFirst().getRcpts());
    }

    @Test
    void outboundTransportDoesNotInheritSubmissionState() {
        Session session = new Session()
                .setAuth(true).setUsername("victim@example.test").setPassword("submission-secret")
                .setTls(true).setStartTls(true).setSecurePort(true)
                .setEhlo("untrusted-client.test").setHelo("untrusted-helo.test")
                .setLhlo("untrusted-lhlo.test").setEhloAuth(List.of("PLAIN"))
                .setBehaviour(List.of("AUTH"));
        session.putMagic("password", "submission-secret");
        session.addEnvelope(new MessageEnvelope()
                .setMail("victim@example.test").setRcpt("recipient@example.test"));
        SessionRouting routing = new SessionRouting(session, new FixedMxResolver());

        Session outbound = routing.getSessions().getFirst();

        assertFalse(outbound.isAuth());
        assertEquals("", outbound.getUsername());
        assertEquals("", outbound.getPassword());
        assertFalse(outbound.isStartTls());
        assertFalse(outbound.isSecurePort());
        assertTrue(outbound.isTls());
        assertEquals(Config.getServer().getHostname(), outbound.getEhlo());
        assertEquals("", outbound.getHelo());
        assertEquals("", outbound.getLhlo());
        assertTrue(outbound.getEhloAuth().isEmpty());
        assertTrue(outbound.getBehaviour().isEmpty());
        assertFalse(outbound.getMagic().containsKey("password"));
        assertTrue(session.isAuth());
        assertEquals(1, routing.getSessions().size(), "Repeated routing must not duplicate sessions");
    }

    @Test
    void deferredRoutesRetainAllRecipientsWithoutDns() {
        Session session = new Session().setAuth(true).setPassword("never-queue-this");
        session.addEnvelope(new MessageEnvelope().setMail("sender@example.test")
                .setRcpts(List.of("one@missing.invalid", "two@unavailable.invalid", "three@missing.invalid")));
        MXResolver noDns = new MXResolver() {
            @Override
            public List<MXRoute> resolveRoutes(List<String> domains) {
                throw new AssertionError("Enqueue must not perform DNS");
            }
        };
        List<Session> routes = new SessionRouting(session, noDns).getDeferredSessions();
        assertEquals(2, routes.size());
        assertEquals(List.of("missing.invalid"), routes.getFirst().getMx());
        assertEquals(List.of("one@missing.invalid", "three@missing.invalid"),
                routes.getFirst().getEnvelopes().getFirst().getRcpts());
        assertEquals(List.of("two@unavailable.invalid"), routes.getLast().getEnvelopes().getFirst().getRcpts());
        assertFalse(routes.getFirst().isAuth());
        assertEquals("", routes.getFirst().getPassword());
    }

    private static class FixedMxResolver extends MXResolver {
        @Override
        public List<MXRoute> resolveRoutes(List<String> domains) {
            FixedRoute route = new FixedRoute();
            for (String domain : domains) {
                route.addDomain(domain);
            }
            return List.of(route);
        }
    }

    private static class FixedRoute extends MXRoute {
        FixedRoute() {
            super("fixed", List.of(new MXServer("mx.example.test", 1)));
        }

        @Override
        public List<String> getIpAddresses() {
            return List.of("192.0.2.25");
        }
    }
}
