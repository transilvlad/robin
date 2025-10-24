package com.mimecast.robin.mx;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Session routing based on MX resolution.
 * <p>Takes a single session with multiple envelopes and recipients,
 * resolves MX records for recipient domains, and splits the session
 * into multiple sessions routed to the appropriate MX servers.
 */
public class SessionRouting {
    private final Logger log = LogManager.getLogger(SessionRouting.class);

    /**
     * Session instance.
     */
    private final Session session;

    /**
     * List of created sessions for each route.
     */
    private final List<Session> sessions = new ArrayList<>();

    /**
     * List of resolved MX routes.
     */
    private List<MXRoute> routes = new ArrayList<>();

    public SessionRouting(Session session) {
        this.session = session;
    }

    /**
     * Get the list of relay sessions created for each resolved MX route.
     *
     * @return List.
     */
    public List<Session> getSessions() {
        // Get unique recipient domains from envelopes.
        List<String> domains = new ArrayList<>();
        session.getEnvelopes().forEach(messageEnvelope -> messageEnvelope.getRcpts().forEach(rcpt -> {
            String domain = rcpt.substring(rcpt.indexOf("@") + 1);
            if (!domains.contains(domain)) {
                domains.add(domain);
            }
        }));

        // Resolve routes for domains.
        routes = new MXResolver().resolveRoutes(domains);

        // Create relay sessions for each unique resolved MX.
        for (MXRoute route : routes) {
            // Get the primary MX server (lowest priority).
            if (route.getServers().isEmpty()) {
                continue;
            }

            // Create a new session for this route.
            Session routeSession = session.clone()
                    .clearEnvelopes()
                    .setMx(route.getIpAddresses())
                    .setPort(25);

            // Iterate through all envelopes and split by recipients for this route.
            for (MessageEnvelope envelope : session.getEnvelopes()) {
                List<String> routeRcpts = new ArrayList<>();

                // Filter recipients whose domain matches this route.
                for (String rcpt : envelope.getRcpts()) {
                    String domain = rcpt.substring(rcpt.indexOf("@") + 1);
                    if (route.getDomains().contains(domain)) {
                        routeRcpts.add(rcpt);
                    }
                }

                // If we have recipients for this route, create a new envelope.
                if (!routeRcpts.isEmpty()) {
                    MessageEnvelope routeEnvelope = envelope.clone();
                    routeEnvelope.getRcpts().clear();
                    routeEnvelope.getRcpts().addAll(routeRcpts);

                    routeSession.addEnvelope(routeEnvelope);
                }
            }

            // Only add the session if it has envelopes
            if (!routeSession.getEnvelopes().isEmpty()) {
                sessions.add(routeSession);
            }
        }

        return sessions;
    }

    /**
     * Get the list of resolved MX routes.
     *
     * @return List.
     */
    public List<MXRoute> getRoutes() {
        return routes;
    }
}
