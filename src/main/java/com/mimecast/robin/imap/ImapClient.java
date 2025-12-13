package com.mimecast.robin.imap;

import com.mimecast.robin.main.Config;
import jakarta.mail.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Lightweight IMAP helper used by tests and utilities to fetch messages from a mailbox.
 *
 * <p>This class is intentionally small and focused: it connects to an IMAP server using
 * Jakarta Mail, opens a folder (defaults to INBOX), and exposes convenience methods to
 * fetch all messages or search for a message by its Message-ID header.
 * <p>
 * Usage example:
 * <pre>
 * try (ImapClient client = new ImapClient("imap.example.com", 993, "user", "pass")) {
 *     List<Message> messages = client.fetchEmails();
 * Message m = client.fetchEmailByMessageId("&lt;abc@example.com&gt;");
 * }
 * </pre>
 * <p>
 * Notes:
 * - Port 993 will enable SSL for Jakarta Mail by default.
 * - The client implements AutoCloseable so it can be used in try-with-resources blocks.
 */
public class ImapClient implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ImapClient.class);

    private final String host;
    private final String port;
    private final String username;
    private final String password;
    private final String folder;

    private Store store;
    private Folder mailbox;

    public ImapClient(String host, long port, String username, String password) {
        this(host, port, username, password, "INBOX");
    }

    public ImapClient(String host, long port, String username, String password, String folder) {
        this.host = host;
        this.port = String.valueOf(port);
        this.username = username;
        this.password = password;
        this.folder = folder;
    }

    /**
     * Builds Jakarta Mail session properties for IMAP/IMAPS.
     */
    private Properties buildProperties() {
        Properties props = new Properties();
        boolean ssl = "993".equals(port);

        // Choose protocol; JavaMail sometimes requires explicit "imaps" for implicit TLS.
        props.put("mail.store.protocol", ssl ? "imaps" : "imap");
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", port);
        props.put("mail.imap.ssl.enable", String.valueOf(ssl));
        props.put("mail.imap.ssl.trust", "*");

        // Provide explicit imaps keys as some providers look these up separately.
        if (ssl) {
            props.put("mail.imaps.host", host);
            props.put("mail.imaps.port", port);
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.trust", "*");
        }
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "20000");

        // Ensure not disabled by defaults.
        props.put("mail.imap.auth.login.disable", "false");
        props.put("mail.imap.auth.plain.disable", "false");
        props.put("mail.imap.auth.xoauth2.disable", "false");

        // Debug toggle.
        props.put("mail.debug", Config.getProperties().getStringProperty("imapClientDebug", "false"));

        return props;
    }

    /**
     * Fetches all emails from the configured folder.
     *
     * <p>On error the method logs the exception and returns an empty list.
     *
     * @return List of Messages (possibly empty). Never returns null.
     */
    public List<Message> fetchEmails() {
        Properties props = buildProperties();
        Session session = Session.getInstance(props);
        List<Message> empty = Collections.emptyList();
        try {
            // Use protocol from properties (imap or imaps).
            String protocol = props.getProperty("mail.store.protocol", "imap");
            store = session.getStore(protocol);
            store.connect(host, username, password);

            mailbox = store.getFolder(folder);
            if (mailbox == null) {
                log.error("Folder '{}' not found on host {}", folder, host);
                return empty;
            }
            if (!mailbox.exists()) {
                log.error("Folder '{}' does not exist", folder);
                return empty;
            }
            mailbox.open(Folder.READ_ONLY);

            Message[] msgs = mailbox.getMessages();
            if (msgs == null || msgs.length == 0) {
                return new ArrayList<>();
            }

            return Arrays.asList(msgs);

        } catch (AuthenticationFailedException afe) {
            log.error("IMAP authentication failed for user '{}': {}", username, afe.getMessage());

        } catch (MessagingException me) {
            if (me.getMessage() != null && me.getMessage().toLowerCase().contains("no login methods supported")) {
                log.warn("Server reported no login methods; retrying with STARTTLS downgrade attempt if applicable.");

                // Retry only if we were using implicit SSL incorrectly; try plain imap with STARTTLS if port not 993.
                if (!"993".equals(port)) {
                    try {
                        Properties retryProps = buildProperties();
                        retryProps.put("mail.imap.starttls.enable", "true");
                        retryProps.put("mail.store.protocol", "imap");
                        Session retrySession = Session.getInstance(retryProps);
                        Store retryStore = retrySession.getStore("imap");
                        retryStore.connect(host, username, password);
                        Folder retryMailbox = retryStore.getFolder(folder);
                        if (retryMailbox != null && retryMailbox.exists()) {
                            retryMailbox.open(Folder.READ_ONLY);
                            Message[] msgs = retryMailbox.getMessages();
                            return msgs == null ? new ArrayList<>() : Arrays.asList(msgs);
                        }
                    } catch (Exception re) {
                        log.error("Retry with STARTTLS failed: {}", re.getMessage());
                    }
                }
            } else {
                log.error("MessagingException fetching IMAP messages: {}", me.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error fetching from IMAP: {}", e.getMessage());
        }
        return empty;
    }

    /**
     * Fetches an email by its Message-ID header.
     *
     * <p>The search is a simple linear scan over the messages returned by {@link #fetchEmails()}.
     * Header comparison is done using String.contains() to allow matching with or without
     * surrounding angle brackets.
     *
     * @param messageId The Message-ID to search for (for example: "&lt;abc@example.com&gt;").
     * @return The Message if found, otherwise null.
     */
    public Message fetchEmailByMessageId(String messageId) {
        log.debug("Searching for Message-ID containing: {}", messageId);
        List<Message> messages = fetchEmails();
        log.debug("Found {} messages in mailbox", messages.size());
        for (Message message : messages) {
            String[] headers;
            try {
                headers = message.getHeader("Message-ID");
                if (headers != null && headers.length > 0) {
                    log.debug("Checking Message-ID: {}", headers[0]);
                    if (headers[0].contains(messageId)) {
                        log.debug("Found matching message!");
                        return message;
                    }
                }
            } catch (MessagingException e) {
                log.error("Error retrieving Message-ID header: {}", e.getMessage());
            }
        }

        log.debug("No matching message found");
        return null;
    }

    @Override
    public void close() throws Exception {
        try {
            // Defensive null checks and ensure resources are only closed when opened.
            if (mailbox != null && mailbox.isOpen()) {
                mailbox.close(false);
            }
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.error("Error closing IMAP connection: {}", e.getMessage());
        }
    }
}
