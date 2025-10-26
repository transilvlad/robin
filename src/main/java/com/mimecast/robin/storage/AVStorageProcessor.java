package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.scanners.ClamAVClient;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Storage processor for antivirus scanning using ClamAV.
 */
public class AVStorageProcessor implements StorageProcessor {
    private static final Logger log = LogManager.getLogger(AVStorageProcessor.class);

    /**
     * Processes the email for antivirus scanning using ClamAV.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if the email is clean, false if a virus is found.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    public boolean process(Connection connection, EmailParser emailParser) throws IOException {
        BasicConfig clamAVConfig = Config.getServer().getClamAV();
        byte[] bytes = Files.readAllBytes(Paths.get(connection.getSession().getEnvelopes().getLast().getFile()));

        if (clamAVConfig.getBooleanProperty("enabled")) {
            // Scan the entire email with ClamAV.
            if (!isClean(bytes, "email", clamAVConfig, connection)) {
                return false;
            }

            // Scan each non-text part with ClamAV for improved results if enabled.
            if (clamAVConfig.getBooleanProperty("scanAttachments")) {
                for (MimePart part : emailParser.getParts()) {
                    if (part instanceof FileMimePart) {
                        String partInfo = part.getHeader("content-type") != null ? part.getHeader("content-type").getValue() : "attachment";
                        boolean isClean = isClean(part.getBytes(), partInfo, clamAVConfig, connection);
                        ((FileMimePart) part).getFile().delete();
                        if (!isClean) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    /**
     * Checks if the given byte array is clean of viruses using ClamAV.
     *
     * @param bytes        The byte array to check.
     * @param part         The part of the email being checked.
     * @param clamAVConfig The ClamAV configuration.
     * @param connection   The SMTP connection.
     * @return True if the byte array is clean, false otherwise.
     * @throws IOException If an error occurs while checking for viruses.
     */
    private boolean isClean(byte[] bytes, String part, BasicConfig clamAVConfig, Connection connection) throws IOException {
        ClamAVClient clamAVClient = new ClamAVClient(
                clamAVConfig.getStringProperty("host", "localhost"),
                clamAVConfig.getLongProperty("port", 3310L).intValue()
        );

        if (clamAVClient.isInfected(bytes)) {
            log.warn("Virus found in {}: {}", part, clamAVClient.getViruses());
            String onVirus = clamAVConfig.getStringProperty("onVirus", "reject");
            SmtpMetrics.incrementEmailVirusRejection();

            if ("reject".equalsIgnoreCase(onVirus)) {
                connection.write(String.format(SmtpResponses.VIRUS_FOUND_550, connection.getSession().getUID()));
                return false;
            } else if ("discard".equalsIgnoreCase(onVirus)) {
                log.warn("Virus found, discarding.");
                return true;
            }

        } else {
            log.info("AV scan clean for {}", part.replaceAll("\\s+", " "));
        }

        return true;
    }
}
