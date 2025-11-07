package com.mimecast.robin.imap;

import jakarta.mail.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

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
     * Fetches all emails from the configured folder.
     *
     * <p>On error the method logs the exception and returns an empty list.
     *
     * @return List of Messages (possibly empty). Never returns null.
     */
    public List<Message> fetchEmails() {
        Properties props = new Properties();
        // Configure Jakarta Mail for IMAP
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", port);
        // Enable SSL when standard IMAPs port is used (993)
        props.put("mail.imap.ssl.enable", port.equals("993") ? "true" : "false");

        Session session = Session.getInstance(props);
        try {
            store = session.getStore("imap");
            // Connect using username/password (no special auth mechanisms are configured here)
            store.connect(host, username, password);

            mailbox = store.getFolder(folder);
            mailbox.open(Folder.READ_ONLY);

            // mailbox.getMessages() returns Message[]; convert to a mutable List correctly.
            Message[] msgs = mailbox.getMessages();
            if (msgs == null || msgs.length == 0) {
                return new ArrayList<>();
            }
            return Arrays.asList(msgs);

        } catch (Exception e) {
            log.error("Error fetching from IMAP: {}", e.getMessage());
        }

        return new ArrayList<>();
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
        List<Message> messages = fetchEmails();
        for (Message message : messages) {
            String[] headers;
            try {
                headers = message.getHeader("Message-ID");
                if (headers != null && headers.length > 0 && headers[0].contains(messageId)) {
                    return message;
                }
            } catch (MessagingException e) {
                log.error("Error retrieving Message-ID header: {}", e.getMessage());
            }
        }

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
