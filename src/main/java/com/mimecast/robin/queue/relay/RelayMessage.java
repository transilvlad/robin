package com.mimecast.robin.queue.relay;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.queue.RelayQueue;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Relay message.
 */
public class RelayMessage {
    private static final Logger log = LogManager.getLogger(RelayMessage.class);

    private final Connection connection;
    private final EmailParser parser;
    private final String file;

    public RelayMessage(Connection connection, EmailParser parser) {
        this.connection = connection;
        this.parser = parser;
        this.file = connection.getSession().getEnvelopes().getLast().getFile();
    }

    public void relay() {
        Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-relay");
        BasicConfig relayConfig = Config.getServer().getRelay();

        // Prepare new envelope.
        MessageEnvelope envelope = new MessageEnvelope();
        if (optional.isPresent() || relayConfig.getBooleanProperty("enabled")) {
            envelope.setMail(connection.getSession().getMail().getAddress())
                    .setRcpts(connection.getSession().getRcpts()
                            .stream()
                            .map(InternetAddress::getAddress)
                            .collect(Collectors.toList()))
                    .setFile(file);
        }

        // Sessions for relay.
        final List<Session> sessions = new ArrayList<>();

        // Check for relay header if not disabled.
        if (!relayConfig.getBooleanProperty("disableRelayHeader")) {
            optional.ifPresent(header -> sessions.add(getRelaySession(header, envelope)));
        }

        // Check relay enabled.
        if (relayConfig.getBooleanProperty("enabled")) {
            sessions.add(getRelaySession(relayConfig, envelope));
        }

        // Deliver sessions if any.
        if (!sessions.isEmpty()) {
            log.info("Relaying session: {}", sessions.size());
            for (Session session : sessions) {
                // Wrap into a relay session.
                RelaySession relaySession = new RelaySession(session)
                        .setMailbox(relayConfig.getStringProperty("mailbox"))
                        .setProtocol(relayConfig.getStringProperty("protocol", "ESMTP"));

                // Enqueue for relay.
                new RelayQueue().enqueue(relaySession);
            }
        }
    }

    /**
     * Gets a new relay session from the relay header.
     *
     * @param header   Relay header.
     * @param envelope MessageEnvelope instance.
     * @return Session instance.
     */
    protected Session getRelaySession(MimeHeader header, MessageEnvelope envelope) {
        Session session = Factories.getSession()
                .addEnvelope(envelope);

        if (header.getValue().contains(":")) {
            String[] splits = header.getValue().split(":");
            session.setMx(Collections.singletonList(splits[0]));
            if (splits.length > 1) {
                session.setPort(Integer.parseInt(splits[1]));
            }
        } else {
            session.setMx(Collections.singletonList(header.getValue()));
        }

        log.info("Relay found for: {}:{}", session.getMx(), session.getPort());

        return session;
    }

    /**
     * Gets a new relay session from the relay header.
     *
     * @param relayConfig Relay configuration.
     * @return Session instance.
     */
    protected Session getRelaySession(BasicConfig relayConfig, MessageEnvelope envelope) {
        Session session = Factories.getSession()
                .setUID(connection.getSession().getUID())
                .setMx(Collections.singletonList(relayConfig.getStringProperty("host")))
                .setPort(Math.toIntExact(relayConfig.getLongProperty("port")))
                .setTls(relayConfig.getBooleanProperty("tls"))
                .addEnvelope(envelope);

        if (relayConfig.getStringProperty("protocol").equalsIgnoreCase("smtp")) {
            session.setHelo(Config.getServer().getHostname());
        } else if (relayConfig.getStringProperty("protocol").equalsIgnoreCase("lmtp")) {
            session.setLhlo(Config.getServer().getHostname());
        } else {
            session.setEhlo(Config.getServer().getHostname());
        }

        return session;
    }
}
