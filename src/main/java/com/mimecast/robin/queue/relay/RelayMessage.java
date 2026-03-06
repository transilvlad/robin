package com.mimecast.robin.queue.relay;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.RspamdConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mx.SessionRouting;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.scanners.DkimSigningLookup;
import com.mimecast.robin.signing.DkimSigner;
import com.mimecast.robin.signing.NativeDkimSigner;
import com.mimecast.robin.signing.RspamdDkimSigner;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    protected BasicConfig relayConfig = Config.getServer().getRelay();

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
     * Sets the relay configuration.
     *
     * @param relayConfig Relay configuration.
     * @return RelayMessage instance.
     */
    public RelayMessage setRelayConfig(BasicConfig relayConfig) {
        this.relayConfig = relayConfig;
        return this;
    }

    /**
     * Relay the message based on the connection and parser.
     *
     * @return List of Session instances created for relay.
     */
    public List<Session> relay() {
        // Sessions for relay.
        final List<Session> sessions = new ArrayList<>();

        // Check if parser given and relay header if not disabled.
        if (parser != null) {
            Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-relay");
            if (!relayConfig.getBooleanProperty("disableRelayHeader")) {
                optional.ifPresent(header -> sessions.add(getRelaySession(header, connection.getSession().getEnvelopes().getLast())));
            }
        }

        // Inbound relay if enabled.
        if (connection.getSession().isInbound() && relayConfig.getBooleanProperty("enabled")) {
            sessions.add(getRelaySession(relayConfig, connection.getSession().getEnvelopes().getLast()));
        }

        // Outbound relay if enabled.
        if (connection.getSession().isOutbound() && relayConfig.getBooleanProperty("outboundEnabled")) {
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
                // Wrap into a relay session. Folder selection is protocol-specific.
                RelaySession relaySession = new RelaySession(session)
                        .setProtocol("esmtp");

                // Set mailbox folder only for LDA protocol (LMTP handles folder routing based on direction).
                if ("dovecot-lda".equalsIgnoreCase(relaySession.getProtocol())) {
                    String folder = connection.getSession().isInbound()
                            ? Config.getServer().getDovecot().getSaveLda().getInboxFolder()
                            : Config.getServer().getDovecot().getSaveLda().getSentFolder();
                    relaySession.setMailbox(folder);
                }

                // Persist any envelope files to storage/queue before enqueueing.
                QueueFiles.persistEnvelopeFiles(relaySession);

                // Apply DKIM signatures for outbound emails before delivery.
                if (connection.getSession().isOutbound()) {
                    applyDkimSignaturesIfEnabled(relaySession);
                }

                // Apply IP pool selection if configured.
                IpPoolSelector poolSelector = Factories.getIpPoolSelector();
                String poolKey = poolSelector.selectPoolKey(relaySession.getSession());
                relaySession.setPoolKey(poolKey);
                String bindIp = poolSelector.selectAddress(poolKey);
                if (bindIp != null) {
                    relaySession.getSession().setBind(bindIp);
                    log.debug("IP pool bind: session={} pool={} ip={}", relaySession.getUID(), poolKey, bindIp);
                }

                // Enqueue for relay delivery.
                PersistentQueue.getInstance()
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
     * Applies DKIM signatures to all envelopes in the relay session if DKIM signing is configured.
     * <p>
     * For each envelope with a file path, looks up signing domain/selector pairs from the
     * configured database, calls Rspamd to sign with each pair, and prepends the resulting
     * {@code DKIM-Signature} headers to the email file. The file is signed once here —
     * retries deliver the already-signed queue file unchanged.
     *
     * @param relaySession Relay session whose envelope files are to be signed.
     */
    private void applyDkimSignaturesIfEnabled(RelaySession relaySession) {
        RspamdConfig rspamdConfig = Config.getServer().getRspamd();
        RspamdConfig.DkimSigningConfig signingConfig = rspamdConfig.getDkimSigning();
        if (!signingConfig.isEnabled()) return;

        String keyPathTemplate = signingConfig.getKeyPath();
        if (keyPathTemplate.isEmpty()) {
            log.warn("DKIM signing enabled but keyPath not configured; skipping signing");
            return;
        }

        // Resolve signer: plugin override takes precedence over config-based backend selection.
        DkimSigner signer = Factories.getDkimSigner();
        if (signer == null) {
            if ("native".equalsIgnoreCase(signingConfig.getBackend())) {
                signer = new NativeDkimSigner();
            } else {
                signer = new RspamdDkimSigner(rspamdConfig.getHost(), rspamdConfig.getPort());
            }
        }

        for (MessageEnvelope envelope : relaySession.getSession().getEnvelopes()) {
            if (envelope.getFile() == null) continue;
            File emailFile = new File(envelope.getFile());
            if (!emailFile.exists()) continue;

            String senderDomain = extractSenderDomain(envelope.getMail());
            if (senderDomain == null) continue;

            List<String[]> signingOptions = DkimSigningLookup.getInstance(signingConfig).lookup(senderDomain);
            log.debug("DKIM signing: {} option(s) for domain {}", signingOptions.size(), senderDomain);

            List<String> signatures = new ArrayList<>();
            for (String[] opt : signingOptions) {
                String domain = opt[0];
                String selector = opt[1];
                String keyPath = keyPathTemplate.replace("$domain", domain).replace("$selector", selector);
                try {
                    String privateKey = readPrivateKey(keyPath);
                    Optional<String> sig = signer.sign(emailFile, domain, selector, privateKey);
                    if (sig.isPresent()) {
                        signatures.add(sig.get());
                        log.debug("DKIM signature obtained: domain={} selector={}", domain, selector);
                    } else {
                        log.warn("No DKIM signature returned: domain={} selector={}", domain, selector);
                    }
                } catch (IOException e) {
                    log.warn("Cannot read DKIM key at {}: {}", keyPath, e.getMessage());
                }
            }

            if (!signatures.isEmpty()) {
                try {
                    prependDkimSignatures(emailFile, signatures);
                } catch (IOException e) {
                    log.error("Failed to prepend DKIM signatures to {}: {}", emailFile.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Reads a PKCS8 PEM private key file and returns the base64 content without PEM headers.
     *
     * @param keyPath Path to the PEM key file.
     * @return Base64 key content (no PEM headers/footers, single line).
     * @throws IOException If the file cannot be read.
     */
    private String readPrivateKey(String keyPath) throws IOException {
        return Files.readString(Path.of(keyPath)).lines()
                .filter(line -> !line.startsWith("-----"))
                .collect(java.util.stream.Collectors.joining());
    }

    /**
     * Prepends {@code DKIM-Signature} headers to the email file.
     *
     * @param emailFile  Email file to modify in place.
     * @param signatures List of DKIM-Signature header values (already RFC 5322 folded by Rspamd).
     * @throws IOException If the file cannot be read or written.
     */
    private void prependDkimSignatures(File emailFile, List<String> signatures) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String sig : signatures) {
            sb.append("DKIM-Signature: ").append(sig).append("\r\n");
        }
        byte[] headers = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] original = Files.readAllBytes(emailFile.toPath());
        byte[] combined = new byte[headers.length + original.length];
        System.arraycopy(headers, 0, combined, 0, headers.length);
        System.arraycopy(original, 0, combined, headers.length, original.length);
        Files.write(emailFile.toPath(), combined);
        log.debug("Prepended {} DKIM-Signature header(s) to {}", signatures.size(), emailFile.getName());
    }

    /**
     * Extracts the domain part from an email address.
     *
     * @param mail Email address (e.g., {@code "user@example.com"}).
     * @return Domain, or null if no {@code @} is present.
     */
    private String extractSenderDomain(String mail) {
        if (mail == null) return null;
        int at = mail.lastIndexOf('@');
        return at >= 0 && at < mail.length() - 1 ? mail.substring(at + 1) : null;
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
                .addEnvelope(envelope.clone());

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
