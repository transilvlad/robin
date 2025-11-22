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

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Storage processor for antivirus scanning using ClamAV.
 */
public class AVStorageProcessor extends AbstractStorageProcessor {
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
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        BasicConfig clamAVConfig = Config.getServer().getClamAV();

        if (clamAVConfig.getBooleanProperty("enabled")) {
            // Scan the entire email with ClamAV.
            if (!isClean(new File(connection.getSession().getEnvelopes().getLast().getFile()), "RAW", clamAVConfig, connection)) {
                return false;
            }

            // Scan each non-text part with ClamAV for improved results if enabled.
            if (clamAVConfig.getBooleanProperty("scanAttachments")) {
                for (MimePart part : emailParser.getParts()) {
                    if (part instanceof FileMimePart) {
                        String partInfo = part.getHeader("content-type") != null ?
                                part.getHeader("content-type").getValue().replaceAll("\\s+", " ") :
                                "unknown attachment";

                        if (!isClean(((FileMimePart) part).getFile(), partInfo, clamAVConfig, connection)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    /**
     * Checks if the given file is clean of viruses using ClamAV.
     *
     * @param file         The file to check.
     * @param partInfo     The partInfo of the email being checked.
     * @param clamAVConfig The ClamAV configuration.
     * @param connection   The SMTP connection.
     * @return True if the file is clean, false otherwise.
     * @throws IOException If an error occurs while checking for viruses.
     */
    private boolean isClean(File file, String partInfo, BasicConfig clamAVConfig, Connection connection) throws IOException {
        ClamAVClient clamAVClient = new ClamAVClient(
                clamAVConfig.getStringProperty("host", "localhost"),
                clamAVConfig.getLongProperty("port", 3310L).intValue()
        );

        if (clamAVClient.isInfected(file)) {
            log.warn("Virus found in {}: {}", partInfo, clamAVClient.getViruses());
            
            // Save virus detection to scan results
            Map<String, Collection<String>> viruses = clamAVClient.getViruses();
            if (viruses != null && !viruses.isEmpty()) {
                Map<String, Object> clamavResult = new HashMap<>();
                clamavResult.put("scanner", "clamav");
                clamavResult.put("infected", true);
                clamavResult.put("viruses", viruses);
                clamavResult.put("part", partInfo);
                var envelopes = connection.getSession().getEnvelopes();
                if (!envelopes.isEmpty()) {
                    envelopes.getLast().addScanResult(clamavResult);
                }
            }
            
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
            log.info("AV scan clean for {}", partInfo);
            
            // Save clean scan result to scan results
            Map<String, Object> clamavResult = new HashMap<>();
            clamavResult.put("scanner", "clamav");
            clamavResult.put("infected", false);
            clamavResult.put("part", partInfo);
            var envelopes = connection.getSession().getEnvelopes();
            if (!envelopes.isEmpty()) {
                envelopes.getLast().addScanResult(clamavResult);
            }
        }

        return true;
    }
}
