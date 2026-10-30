package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.RspamdConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.scanners.RspamdClient;
import com.mimecast.robin.scanners.RspamdMessageMutations;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Storage processor for spam scanning using Rspamd.
 */
public class SpamStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(SpamStorageProcessor.class);

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
            if (!action.isEmpty() && !java.util.Set.of("no action", "add header", "rewrite subject").contains(action)) {
                throw new IOException("Unsupported Rspamd action");
            }
            boolean signed = RspamdMessageMutations.apply(envelope, scanResult);
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
        }

        return true;
    }

}
