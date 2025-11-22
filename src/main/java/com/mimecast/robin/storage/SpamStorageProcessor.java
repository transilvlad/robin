package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.RspamdConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.scanners.RspamdClient;
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
        if (rspamdConfig.isEnabled()) {
            File emailFile = new File(connection.getSession().getEnvelopes().getLast().getFile());
            RspamdClient rspamdClient = new RspamdClient(
                    rspamdConfig.getHost(),
                    rspamdConfig.getPort())
                    .setEmailDirection(connection.getSession().getDirection())
                    .setSpfScanEnabled(rspamdConfig.isSpfScanEnabled())
                    .setDkimScanEnabled(rspamdConfig.isDkimScanEnabled())
                    .setDmarcScanEnabled(rspamdConfig.isDmarcScanEnabled());

            // Scan the email and retrieve the score
            Map<String, Object> scanResult = rspamdClient.scanFile(emailFile);
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
            if (score >= discardThreshold) {
                log.warn("Spam/phishing detected in {} with score {} (>= discard threshold {}), discarding: {}", 
                         connection.getSession().getEnvelopes().getLast().getFile(), score, discardThreshold, rspamdClient.getSymbols());
                SmtpMetrics.incrementEmailSpamRejection();
                return true;  // Accept but discard
            } else if (score >= rejectThreshold) {
                log.warn("Spam/phishing detected in {} with score {} (>= reject threshold {}): {}", 
                         connection.getSession().getEnvelopes().getLast().getFile(), score, rejectThreshold, rspamdClient.getSymbols());
                SmtpMetrics.incrementEmailSpamRejection();
                connection.write(String.format(SmtpResponses.SPAM_FOUND_550, connection.getSession().getUID()));
                return false;  // Reject
            } else {
                log.info("Spam scan clean with score {}", score);
            }
        }

        return true;
    }
}
