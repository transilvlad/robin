package com.mimecast.robin.mx;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
