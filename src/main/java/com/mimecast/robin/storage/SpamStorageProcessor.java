package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.scanners.RspamdClient;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Storage processor for spam scanning using Rspamd.
 */
public class SpamStorageProcessor implements StorageProcessor {
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
    public boolean process(Connection connection, EmailParser emailParser) throws IOException {
        BasicConfig rspamdConfig = Config.getServer().getRspamd();
        if (rspamdConfig.getBooleanProperty("enabled")) {
            byte[] bytes = Files.readAllBytes(Paths.get(connection.getSession().getEnvelopes().getLast().getFile()));
            RspamdClient rspamdClient = new RspamdClient(
                    rspamdConfig.getStringProperty("host", "localhost"),
                    rspamdConfig.getLongProperty("port", 11333L).intValue())
                    .setEmailDirection(connection.getSession().getDirection())
                    .setSpfScanEnabled(rspamdConfig.getBooleanProperty("spfScanEnabled"))
                    .setDkimScanEnabled(rspamdConfig.getBooleanProperty("dkimScanEnabled"))
                    .setDmarcScanEnabled(rspamdConfig.getBooleanProperty("dmarcScanEnabled"));

            // Scan the email and retrieve the score
            rspamdClient.scanBytes(bytes);
            double score = rspamdClient.getScore();
            
            // Get thresholds with defaults
            double discardThreshold = rspamdConfig.getDoubleProperty("discardThreshold", 15.0);
            double rejectThreshold = rspamdConfig.getDoubleProperty("rejectThreshold", 7.0);
            
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
