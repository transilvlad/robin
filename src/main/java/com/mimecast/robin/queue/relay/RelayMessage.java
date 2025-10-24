package com.mimecast.robin.queue.relay;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mx.SessionRouting;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Relay message.
 */
public class RelayMessage {
    private static final Logger log = LogManager.getLogger(RelayMessage.class);

    private final Connection connection;
    private final EmailParser parser;

    /**
     * Constructs a RelayMessage with the given connection and optional parser.
     *
     * @param connection Connection instance.
     */
    public RelayMessage(Connection connection) {
        this(connection, null);
    }

    /**
     * Constructs a RelayMessage with the given connection and parser.
     *
     * @param connection Connection instance.
     * @param parser     EmailParser instance.
     */
    public RelayMessage(Connection connection, EmailParser parser) {
        this.connection = connection;
        this.parser = parser;
    }

    /**
     * Relay the message based on the connection and parser.
     *
     * @return List of Session instances created for relay.
     */
    public List<Session> relay() {
        BasicConfig relayConfig = Config.getServer().getRelay();

        // Sessions for relay.
        final List<Session> sessions = new ArrayList<>();

        // Check if parser given and relay header if not disabled.
        if (parser != null) {
            Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-relay");
            if (!relayConfig.getBooleanProperty("disableRelayHeader")) {
                optional.ifPresent(header -> sessions.add(getRelaySession(header, connection.getSession().getEnvelopes().getLast())));
            }
        }

        String mailbox = relayConfig.getStringProperty("mailbox");

        // Inbound relay if enabled.
        if (connection.getSession().isInbound() && relayConfig.getBooleanProperty("enabled")) {
            sessions.add(getRelaySession(relayConfig, connection.getSession().getEnvelopes().getLast()));
        }

        // Outbound relay if enabled.
        if (connection.getSession().isOutbound() && relayConfig.getBooleanProperty("outboundEnabled")) {
            mailbox = relayConfig.getStringProperty("outbox");

            // Outbound MX relay if enabled.
            if (relayConfig.getBooleanProperty("outboundMxEnabled")) {
                // Resolve MX and create sessions.
                sessions.addAll(new SessionRouting(connection.getSession()).getSessions());
            }
        }

        // Enqueue sessions if any.
        if (!sessions.isEmpty()) {
            log.info("Relaying session: {}", sessions.size());
            for (Session session : sessions) {
                // Wrap into a relay session.
                RelaySession relaySession = new RelaySession(session)
                        .setMailbox(mailbox)
                        .setProtocol("ESMTP");

                // Persist any envelope files to storage/queue before enqueueing.
                QueueFiles.persistEnvelopeFiles(relaySession);

                // Enqueue for retry.
                PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE)
                        .enqueue(relaySession);
            }
        }

        return sessions;
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
