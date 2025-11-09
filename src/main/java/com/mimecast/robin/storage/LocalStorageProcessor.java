package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ReceivedHeader;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.util.PathUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Local storage processor for mailbox storage.
 * <p>Copies emails from tmp storage to recipient-specific mailbox folders.
 */
public class LocalStorageProcessor implements StorageProcessor {
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
    public boolean process(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig config = Config.getServer();

        // Check if local mailbox storage is enabled
        if (!config.getStorage().getBooleanProperty("localMailbox")) {
            log.debug("Local mailbox storage disabled by configuration (localMailbox=false). Skipping.");
            return true;
        }

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for local storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        String sourceFile = envelope.getFile();

        if (sourceFile == null || !new File(sourceFile).exists()) {
            log.error("Source file does not exist for local storage processing: {}", sourceFile);
            return false;
        }

        // Handle outbound vs inbound differently
        if (connection.getSession().isOutbound()) {
            // For outbound, save once to sender's outbox
            saveToOutbox(connection, emailParser, sourceFile, envelope.getMail());
        } else {
            // For inbound, save to each recipient's mailbox
            saveToRecipientMailboxes(connection, emailParser, sourceFile, envelope.getRcpts());
        }

        log.debug("Completed local storage processing for uid={}", connection.getSession().getUID());
        return true;
    }

    /**
     * Save email to sender's outbox (for outbound emails).
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @param sourceFile  Source email file path.
     * @param sender      Sender email address.
     * @throws IOException If an I/O error occurs.
     */
    private void saveToOutbox(Connection connection, EmailParser emailParser, String sourceFile, String sender) throws IOException {
        ServerConfig config = Config.getServer();
        String basePath = config.getStorage().getStringProperty("path", "/tmp/store");
        String mailbox = Config.getServer().getRelay().getStringProperty("outbox", "Sent");

        // Parse sender email to get domain and username
        String[] splits = sender.split("@");
        if (splits.length != 2) {
            log.warn("Invalid sender email format: {}", sender);
            return;
        }

        String domain = PathUtils.normalize(splits[1]);
        String username = PathUtils.normalize(splits[0]);

        // Build destination path: basePath/domain/username/mailbox/
        String destPath = Paths.get(basePath, domain, username, mailbox).toString();

        // Create directory if needed
        if (!PathUtils.makePath(destPath)) {
            log.error("Failed to create destination path: {}", destPath);
            throw new IOException("Failed to create destination path: " + destPath);
        }

        // Generate destination filename
        String fileName = new File(sourceFile).getName();
        String destFile = Paths.get(destPath, fileName).toString();

        // Prepend received header (without "for recipient" field)
        ReceivedHeader receivedHeader = new ReceivedHeader(connection);
        saveEmailWithHeader(sourceFile, destFile, receivedHeader.toString(), emailParser);

        log.info("Saved outbound email to sender outbox: {}", destFile);
    }

    /**
     * Save email to each recipient's mailbox (for inbound emails).
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @param sourceFile  Source email file path.
     * @param recipients  List of recipient email addresses.
     * @throws IOException If an I/O error occurs.
     */
    private void saveToRecipientMailboxes(Connection connection, EmailParser emailParser, String sourceFile, List<String> recipients) throws IOException {
        ServerConfig config = Config.getServer();
        String basePath = config.getStorage().getStringProperty("path", "/tmp/store");
        String mailbox = Config.getServer().getRelay().getStringProperty("mailbox", "INBOX");

        for (String recipient : recipients) {
            // Parse recipient email to get domain and username
            String[] splits = recipient.split("@");
            if (splits.length != 2) {
                log.warn("Invalid recipient email format: {}", recipient);
                continue;
            }

            String domain = PathUtils.normalize(splits[1]);
            String username = PathUtils.normalize(splits[0]);

            // Build destination path: basePath/domain/username/mailbox/
            String destPath = Paths.get(basePath, domain, username, mailbox).toString();

            // Create directory if needed
            if (!PathUtils.makePath(destPath)) {
                log.error("Failed to create destination path: {}", destPath);
                continue;
            }

            // Generate destination filename
            String fileName = new File(sourceFile).getName();
            String destFile = Paths.get(destPath, fileName).toString();

            // Prepend received header with recipient
            ReceivedHeader receivedHeader = new ReceivedHeader(connection);
            receivedHeader.setRecipientAddress(recipient);
            saveEmailWithHeader(sourceFile, destFile, receivedHeader.toString(), emailParser);

            log.info("Saved inbound email to recipient mailbox: {} for recipient: {}", destFile, recipient);
        }
    }

    /**
     * Save email with prepended Received header.
     *
     * @param sourceFile     Source email file path.
     * @param destFile       Destination email file path.
     * @param receivedHeader Received header string.
     * @param emailParser    EmailParser instance (unused but kept for consistency).
     * @throws IOException If an I/O error occurs.
     */
    private void saveEmailWithHeader(String sourceFile, String destFile, String receivedHeader, EmailParser emailParser) throws IOException {
        // Read source file content
        byte[] sourceContent = Files.readAllBytes(Paths.get(sourceFile));

        // Write received header followed by source content to destination
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            fos.write(receivedHeader.getBytes());
            fos.write(sourceContent);
        }
    }
}
