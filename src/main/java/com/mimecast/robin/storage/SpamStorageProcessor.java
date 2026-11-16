package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.RspamdConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.scanners.AuthenticationHeaders;
import com.mimecast.robin.scanners.RspamdClient;
import com.mimecast.robin.scanners.RspamdMessageMutations;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Storage processor for spam scanning using Rspamd.
 */
public class SpamStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(SpamStorageProcessor.class);
    private static final String AUTHENTICATION_RESULTS = "Authentication-Results";
    private static final Set<String> SPAM_HEADERS = Set.of("x-spam-status", "x-spam-score", "x-spam-flag");

    private final AuthenticationHeaders.Resolver resolver;

    public SpamStorageProcessor() {
        this(AuthenticationHeaders.DNS);
    }

    SpamStorageProcessor(AuthenticationHeaders.Resolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Processes the email for spam scanning using Rspamd.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if the email is not spam, false if spam is detected.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        RspamdConfig rspamdConfig = Config.getServer().getRspamd();
        boolean signingRequired = rspamdConfig.isDkimSigningRequired()
                && connection.getSession().isAuth() && connection.getSession().isOutbound();
        if (signingRequired && !rspamdConfig.isEnabled()) {
            throw new IOException("Required Rspamd DKIM signer is disabled");
        }
        if (rspamdConfig.isEnabled()) {
            var envelope = connection.getSession().getEnvelopes().getLast();
            var session = connection.getSession();
            RspamdClient rspamdClient = new RspamdClient(
                    rspamdConfig.getHost(),
                    rspamdConfig.getPort(),
                    rspamdConfig.getTimeout())
                    .setEmailDirection(session.getDirection())
                    .setSpfScanEnabled(rspamdConfig.isSpfScanEnabled())
                    .setDkimScanEnabled(rspamdConfig.isDkimScanEnabled())
                    .setDmarcScanEnabled(rspamdConfig.isDmarcScanEnabled())
                    .setSmtpContext(
                            session.getFriendAddr(),
                            session.getEhlo() != null && !session.getEhlo().isBlank() ? session.getEhlo() : session.getHelo(),
                            session.getFriendRdns(),
                            envelope.getMail(),
                            envelope.getRcpt())
                    .setRecipients(envelope.getRcpts())
                    .setAuthenticatedUser(session.isAuth() ? session.getUsername() : null)
                    .setSettingsId(session.isOutbound() ? rspamdConfig.getOutboundSettingsId() : rspamdConfig.getInboundSettingsId());

            // Scan the email and retrieve the score.
            // Stream file-backed messages from disk; in-memory messages are posted directly.
            Map<String, Object> scanResult = envelope.hasMaterializedFile()
                    ? rspamdClient.scanFile(new File(envelope.getFile()))
                    : rspamdClient.scanBytes(envelope.readMessageBytes());
            double score = rspamdClient.getScore();

            // Save scan results to envelope
            if (!scanResult.isEmpty()) {
                Map<String, Object> rspamdResult = new HashMap<>(scanResult);
                rspamdResult.put("scanner", "rspamd");
                var envelopes = connection.getSession().getEnvelopes();
                if (!envelopes.isEmpty()) {
                    envelopes.getLast().addScanResult(rspamdResult);
                }
            }

            String action = scanResult.get("action") instanceof String value
                    ? value.toLowerCase(java.util.Locale.ROOT) : "";
            if (scanResult.get("milter") instanceof Map<?, ?> milter && milter.get("reject") instanceof String value) {
                action = value.toLowerCase(java.util.Locale.ROOT);
            }
            if ("soft reject".equals(action) || "greylist".equals(action)) {
                connection.write("451 4.7.1 Message temporarily rejected by spam policy");
                connection.markSmtpResponseSent();
                return false;
            }
            if ("reject".equals(action) || ("discard".equals(action) && !rspamdConfig.isDiscardEnabled())) {
                connection.write(String.format(SmtpResponses.SPAM_FOUND_550, session.getUID()));
                connection.markSmtpResponseSent();
                SmtpMetrics.incrementEmailSpamRejection();
                return false;
            }
            if ("discard".equals(action)) {
                log.warn("Rspamd discard explicitly enabled; accepting and discarding message");
                envelope.setBlackholed(true);
                return true;
            }
            if (!action.isEmpty() && !Set.of("no action", "add header", "rewrite subject").contains(action)) {
                throw new IOException("Unsupported Rspamd action");
            }
            List<String[]> trace = traceHeaders(connection, envelope, scanResult);
            boolean signed = RspamdMessageMutations.apply(envelope, scanResult, trace, traceRemoval(trace));
            if (signingRequired && !signed) {
                throw new IOException("Required Rspamd DKIM signature is missing");
            }
            if (!action.isEmpty()) {
                return true;
            }

            // Get thresholds with defaults
            double discardThreshold = rspamdConfig.getDiscardThreshold();
            double rejectThreshold = rspamdConfig.getRejectThreshold();

            // Validate thresholds - discardThreshold should be >= rejectThreshold
            if (discardThreshold < rejectThreshold) {
                log.warn("Invalid threshold configuration: discardThreshold ({}) is less than rejectThreshold ({}). Using rejectThreshold as discardThreshold.",
                        discardThreshold, rejectThreshold);
                discardThreshold = rejectThreshold;
            }

            // Apply threshold-based logic
            if (rspamdConfig.isDiscardEnabled() && score >= discardThreshold) {
                log.warn("Spam/phishing detected in {} with score {} (>= discard threshold {}), discarding: {}",
                        envelope.getFile(), score, discardThreshold, rspamdClient.getSymbols());
                SmtpMetrics.incrementEmailSpamRejection();
                envelope.setBlackholed(true);
                return true;  // Accept but discard
            } else if (score >= rejectThreshold) {
                log.warn("Spam/phishing detected in {} with score {} (>= reject threshold {}): {}",
                        envelope.getFile(), score, rejectThreshold, rspamdClient.getSymbols());
                SmtpMetrics.incrementEmailSpamRejection();
                connection.write(String.format(SmtpResponses.SPAM_FOUND_550, connection.getSession().getUID()));
                connection.markSmtpResponseSent();
                return false;  // Reject
            } else {
                log.info("Spam scan clean with score {}", score);
            }
        } else if (!connection.getSession().getEnvelopes().isEmpty()) {
            var envelope = connection.getSession().getEnvelopes().getLast();
            List<String[]> trace = traceHeaders(connection, envelope, null);
            if (!trace.isEmpty() && !envelope.isBlackholed() && envelope.hasMessageSource()) {
                RspamdMessageMutations.apply(envelope, Map.of(), trace, traceRemoval(trace));
            }
        }

        return true;
    }

    /**
     * Gets Robin's Authentication-Results and X-Spam-* headers for an inbound message.
     * <p>Outbound mail gets none: they would expose the sender's login and scores to recipients.
     *
     * @param connection Connection instance.
     * @param envelope   MessageEnvelope instance.
     * @param scanResult Rspamd scan result, or null when not scanned.
     * @return Name and value pairs, in order.
     */
    private List<String[]> traceHeaders(Connection connection, MessageEnvelope envelope, Map<String, Object> scanResult) {
        List<String[]> headers = new ArrayList<>();
        if (!connection.getSession().isInbound()) {
            return headers;
        }
        if (Config.getServer().isAuthenticationResultsHeader()) {
            headers.add(new String[]{AUTHENTICATION_RESULTS, AuthenticationHeaders.authenticationResults(
                    Config.getServer().getHostname(), connection.getSession(), envelope, scanResult, resolver)});
        }
        if (Config.getServer().isSpamHeaders()) {
            headers.addAll(AuthenticationHeaders.spamHeaders(scanResult));
        }
        return headers;
    }

    /**
     * Gets the existing headers replaced by Robin's: Authentication-Results claiming its authserv-id
     * (RFC 8601 section 5) and X-Spam-* when Robin adds its own.
     *
     * @param trace Headers Robin adds.
     * @return Removal predicate.
     */
    private static RspamdMessageMutations.Removal traceRemoval(List<String[]> trace) {
        boolean results = trace.stream().anyMatch(header -> header[0].equals(AUTHENTICATION_RESULTS));
        boolean spam = trace.stream().anyMatch(header -> header[0].equals("X-Spam-Status"));
        String authservId = Config.getServer().getHostname();
        return (name, value) -> results && name.equalsIgnoreCase(AUTHENTICATION_RESULTS)
                && AuthenticationHeaders.claims(value, authservId)
                || spam && SPAM_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
