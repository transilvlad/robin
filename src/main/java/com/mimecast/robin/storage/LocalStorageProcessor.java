package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.util.PathUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;

/**
 * Local storage processor for mailbox storage.
 * <p>Copies emails from tmp storage to recipient-specific mailbox folders.
 */
public class LocalStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(LocalStorageProcessor.class);

    /**
     * Processes the email for local mailbox storage.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if processing succeeds.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig config = Config.getServer();

        // Check if local mailbox storage is enabled.
        if (!config.getStorage().getBooleanProperty("localMailbox")) {
            log.debug("Local mailbox storage disabled by configuration (localMailbox=false). Skipping.");
            return true;
        }

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for local storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        if (!envelope.hasMessageSource()) {
            log.error("No message source available for local storage processing");
            return false;
        }

        // Handle outbound vs inbound differently.
        if (connection.getSession().isOutbound()) {
            // For outbound, save once to sender's outbox.
            saveToOutbox(envelope, envelope.getMail());
        } else {
            // For inbound, save to each recipient's mailbox.
            saveToRecipientMailboxes(envelope, envelope.getRcpts());
        }

        log.debug("Completed local storage processing for uid={}", connection.getSession().getUID());
        return true;
    }

    /**
     * Save email to sender's outbox (for outbound emails).
     *
     * @param envelope Source message envelope.
     * @param sender   Sender email address.
     * @throws IOException If an I/O error occurs.
     */
    private void saveToOutbox(MessageEnvelope envelope, String sender) throws IOException {
        ServerConfig config = Config.getServer();
        String basePath = config.getStorage().getStringProperty("path", "/tmp/store");
        String outboundFolder = config.getStorage().getStringProperty("outboundFolder", ".Sent/new");

        // Parse sender email to get domain and username.
        String[] splits = sender.split("@");
        if (splits.length != 2) {
            log.warn("Invalid sender email format: {}", sender);
            return;
        }

        String domain = PathUtils.normalize(splits[1]);
        String username = PathUtils.normalize(splits[0]);

        // Build destination path: basePath/domain/username/outboundFolder/.
        String destPath = Paths.get(basePath, domain, username, outboundFolder).toString();

        // Create directory if needed.
        if (!PathUtils.makePath(destPath)) {
            log.error("Failed to create destination path: {}", destPath);
            throw new IOException("Failed to create destination path: " + destPath);
        }

        // Generate destination filename.
        String sourceFile = envelope.getFile();
        String fileName = new File(sourceFile).getName();
        String destFile = Paths.get(destPath, fileName).toString();

        saveEmail(envelope, destFile);

        log.info("Saved outbound email to sender outbox: {}", destFile);
    }

    /**
     * Save email to each recipient's mailbox (for inbound emails).
     *
     * @param envelope   Source message envelope.
     * @param recipients List of recipient email addresses.
     * @throws IOException If an I/O error occurs.
     */
    private void saveToRecipientMailboxes(MessageEnvelope envelope, List<String> recipients) throws IOException {
        ServerConfig config = Config.getServer();
        String basePath = config.getStorage().getStringProperty("path", "/tmp/store");
        String inboundFolder = config.getStorage().getStringProperty("inboundFolder", "new");

        for (String recipient : recipients) {
            // Skip bot addresses - they are handled by bot processors.
            if (envelope != null && envelope.isBotAddress(recipient)) {
                log.debug("Skipping storage for bot address: {}", recipient);
                continue;
            }

            // Parse recipient email to get domain and username.
            String[] splits = recipient.split("@");
            if (splits.length != 2) {
                log.warn("Invalid recipient email format: {}", recipient);
                continue;
            }

            String domain = PathUtils.normalize(splits[1]);
            String username = PathUtils.normalize(splits[0]);

            // Build destination path: basePath/domain/username/inboundFolder/.
            String destPath = Paths.get(basePath, domain, username, inboundFolder).toString();

            // Create directory if needed.
            if (!PathUtils.makePath(destPath)) {
                log.error("Failed to create destination path: {}", destPath);
                continue;
            }

            // Generate destination filename.
            String sourceFile = envelope.getFile();
            String fileName = new File(sourceFile).getName();
            String destFile = Paths.get(destPath, fileName).toString();

            saveEmail(envelope, destFile);

            log.info("Saved inbound email to recipient mailbox: {} for recipient: {}", destFile, recipient);
        }
    }

    /**
     * Save the received message unchanged.
     *
     * @param envelope Source message envelope.
     * @param destFile Destination email file path.
     * @throws IOException If an I/O error occurs.
     */
    private void saveEmail(MessageEnvelope envelope, String destFile) throws IOException {
        try (InputStream fis = envelope.openMessageStream();
             FileOutputStream fos = new FileOutputStream(destFile)) {
            fis.transferTo(fos);
        }
    }
}
