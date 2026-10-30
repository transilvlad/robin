package com.mimecast.robin.mx;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    private final MXResolver mxResolver;

    /**
     * List of created sessions for each route.
     */
    private final List<Session> sessions = new ArrayList<>();

    /**
     * List of resolved MX routes.
     */
    private List<MXRoute> routes = new ArrayList<>();

    public SessionRouting(Session session) {
        this(session, new MXResolver());
    }

    SessionRouting(Session session, MXResolver mxResolver) {
        this.session = session;
        this.mxResolver = mxResolver;
    }

    /**
     * Get the list of relay sessions created for each resolved MX route.
     *
     * @return List.
     */
    public List<Session> getSessions() {
        sessions.clear();
        // Get unique recipient domains from envelopes.
        List<String> domains = new ArrayList<>();
        session.getEnvelopes().forEach(messageEnvelope -> messageEnvelope.getRcpts().forEach(rcpt -> {
            String domain = rcpt.substring(rcpt.indexOf("@") + 1);
            if (!domains.contains(domain)) {
                domains.add(domain);
            }
        }));

        // Resolve routes for domains.
        routes = mxResolver.resolveRoutes(domains);

        // Create relay sessions for each unique resolved MX.
        for (MXRoute route : routes) {
            // Get the primary MX server (lowest priority).
            if (route.getServers().isEmpty() || route.getIpAddresses().isEmpty()) {
                throw new MailRoutingException("No MX addresses for route " + route.getHash(), false);
            }

            // Create a new session for this route.
            Session routeSession = newOutboundSession()
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
                    routeEnvelope.setRcpts(routeRcpts);

                    routeSession.addEnvelope(routeEnvelope);
                }
            }

            // Only add the session if it has envelopes
            if (!routeSession.getEnvelopes().isEmpty()) {
                sessions.add(routeSession);
            }
        }

        for (String domain : domains) {
            if (routes.stream().noneMatch(route -> route.getDomains().contains(domain))) {
                throw new MailRoutingException("No MX route for " + domain, false);
            }
        }
        return sessions;
    }

    /**
     * Splits envelopes by recipient domain without DNS I/O. Queue these using the
     * {@code mx} protocol so DNS and policy discovery happen on every delivery attempt.
     */
    public List<Session> getDeferredSessions() {
        Map<String, Session> byDomain = new LinkedHashMap<>();
        for (MessageEnvelope envelope : session.getEnvelopes()) {
            Map<String, List<String>> recipients = new LinkedHashMap<>();
            for (String recipient : envelope.getRcpts()) {
                int at = recipient.lastIndexOf('@');
                if (at < 1 || at == recipient.length() - 1) {
                    throw new MailRoutingException("Invalid queued recipient address", true);
                }
                String domain = recipient.substring(at + 1).toLowerCase(Locale.ROOT);
                recipients.computeIfAbsent(domain, key -> new ArrayList<>()).add(recipient);
            }
            recipients.forEach((domain, addresses) -> {
                Session target = byDomain.computeIfAbsent(domain,
                        key -> newOutboundSession().setMx(List.of(key)));
                target.addEnvelope(envelope.clone().setRcpts(new ArrayList<>(addresses)));
            });
        }
        return new ArrayList<>(byDomain.values());
    }

    private Session newOutboundSession() {
        return new Session()
                .setDirection(session.getDirection())
                .setEhlo(Config.getServer().getHostname())
                .setTls(true);
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
