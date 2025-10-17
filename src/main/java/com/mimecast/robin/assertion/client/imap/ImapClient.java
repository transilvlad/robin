package com.mimecast.robin.assertion.client.imap;

import jakarta.mail.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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
     * Fetches all emails from the INBOX folder.
     *
     * @return List of Messages, or null if an error occurs.
     */
    public List<Message> fetchEmails() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", port);
        props.put("mail.imap.ssl.enable", port.equals("993") ? "true" : "false");

        Session session = Session.getInstance(props);
        try {
            store = session.getStore("imap");
            store.connect(host, username, password);

            mailbox = store.getFolder(folder);
            mailbox.open(Folder.READ_ONLY);

            return List.of(mailbox.getMessages());

        } catch (Exception e) {
            log.error("Error fetching from IMAP: {}", e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * Fetches an email by its Message-ID header.
     *
     * @param messageId The Message-ID to search for.
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
            mailbox.close(false);
            store.close();
        } catch (Exception e) {
            log.error("Error closing IMAP connection: {}", e.getMessage());
        }
    }
}
