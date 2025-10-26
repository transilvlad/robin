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

            if (rspamdClient.isSpam(bytes, rspamdConfig.getDoubleProperty("requiredScore", 7.0))) {
                double score = rspamdClient.getScore();
                log.warn("Spam/phishing detected in {} with score {}: {}", connection.getSession().getEnvelopes().getLast().getFile(), score, rspamdClient.getSymbols());
                String onSpam = rspamdConfig.getStringProperty("onSpam", "reject");
                SmtpMetrics.incrementEmailSpamRejection();

                if ("reject".equalsIgnoreCase(onSpam)) {
                    connection.write(String.format(SmtpResponses.SPAM_FOUND_550, connection.getSession().getUID()));
                    return false;
                } else if ("discard".equalsIgnoreCase(onSpam)) {
                    log.warn("Spam/phishing detected, discarding.");
                    return true;
                }
            } else {
                log.info("Spam scan clean with score {}", rspamdClient.getScore());
            }
        }

        return true;
    }
}
