package com.mimecast.robin.imap;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.HashType;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MultipartMimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import jakarta.mail.*;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.SearchTerm;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.io.IOUtils;

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
 *     List&lt;Message&gt; messages = client.fetchEmails();
 *     Message m = client.fetchEmailByMessageId("&lt;abc@example.com&gt;");
 *
 *     // Delete the message after verification
 *     client.deleteMessage(m);
 *
 *     // Or purge all messages in the folder
 *     int deleted = client.purgeFolder();
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
     * Searches for an email by Message-ID using IMAP SEARCH command.
     *
     * <p>This is more efficient than fetching all messages for large mailboxes.
     * The search is performed server-side using the HEADER search key.
     *
     * @param messageId The Message-ID to search for.
     * @return The Message if found, otherwise null.
     */
    public Message searchByMessageId(String messageId) {
        log.debug("Searching for Message-ID using IMAP SEARCH: {}", messageId);
        Properties props = buildProperties();
        Session session = Session.getInstance(props);

        try {
            String protocol = props.getProperty("mail.store.protocol", "imap");
            store = session.getStore(protocol);
            store.connect(host, username, password);

            mailbox = store.getFolder(folder);
            if (mailbox == null || !mailbox.exists()) {
                log.error("Folder '{}' not found or does not exist on host {}", folder, host);
                return null;
            }
            mailbox.open(Folder.READ_ONLY);

            // Use IMAP SEARCH with Message-ID header.
            String searchId = messageId.startsWith("<") ? messageId : "<" + messageId + ">";
            SearchTerm searchTerm = new HeaderTerm("Message-ID", searchId);
            Message[] results = mailbox.search(searchTerm);

            if (results != null && results.length > 0) {
                log.debug("IMAP SEARCH found {} message(s), returning first match", results.length);
                return results[0];
            }

            log.debug("IMAP SEARCH found no matching messages");
            return null;

        } catch (MessagingException me) {
            log.warn("IMAP SEARCH failed: {}", me.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error during IMAP SEARCH: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches an email by its Message-ID header.
     *
     * <p>First attempts to use IMAP SEARCH for efficiency. If SEARCH fails or returns
     * no results, falls back to a linear scan over all messages.
     *
     * @param messageId The Message-ID to search for (for example: "&lt;abc@example.com&gt;").
     * @return The Message if found, otherwise null.
     */
    public Message fetchEmailByMessageId(String messageId) {
        // Try IMAP SEARCH first for efficiency.
        Message result = searchByMessageId(messageId);
        if (result != null) {
            return result;
        }

        // Fallback to linear scan.
        log.debug("Falling back to linear scan for Message-ID: {}", messageId);
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

    /**
     * Deletes a single message from the mailbox.
     *
     * <p>The folder is reopened in READ_WRITE mode if necessary. The message is marked
     * as deleted and then expunged from the server.
     *
     * @param message The message to delete.
     * @return true if deletion was successful, false otherwise.
     */
    public boolean deleteMessage(Message message) {
        if (message == null) {
            log.warn("Cannot delete null message");
            return false;
        }

        try {
            Folder messageFolder = message.getFolder();
            if (messageFolder == null) {
                log.error("Message has no associated folder");
                return false;
            }

            // Capture UID before any close: closing a Jakarta Mail Folder invalidates every
            // Message reference obtained from it, so setFlag after reopen silently fails.
            long uid = (messageFolder instanceof UIDFolder)
                    ? ((UIDFolder) messageFolder).getUID(message)
                    : -1L;

            // Reopen folder in READ_WRITE mode if needed.
            if (messageFolder.isOpen() && messageFolder.getMode() != Folder.READ_WRITE) {
                messageFolder.close(false);
            }
            if (!messageFolder.isOpen()) {
                messageFolder.open(Folder.READ_WRITE);
            }

            Message target = (uid >= 0 && messageFolder instanceof UIDFolder)
                    ? ((UIDFolder) messageFolder).getMessageByUID(uid)
                    : message;
            if (target == null) {
                log.warn("Message no longer exists in folder '{}' (uid={})", messageFolder.getName(), uid);
                return false;
            }
            target.setFlag(Flags.Flag.DELETED, true);
            messageFolder.expunge();
            log.info("Deleted message from folder '{}'", messageFolder.getName());
            return true;

        } catch (MessagingException me) {
            log.error("Failed to delete message: {}", me.getMessage());
            return false;
        }
    }

    /**
     * Purges all messages from the configured folder.
     *
     * <p>All messages in the folder are marked as deleted and then expunged.
     * The folder is opened in READ_WRITE mode for this operation.
     *
     * @return The number of messages deleted, or -1 on error.
     */
    public int purgeFolder() {
        Properties props = buildProperties();
        Session session = Session.getInstance(props);

        try {
            String protocol = props.getProperty("mail.store.protocol", "imap");
            if (store == null || !store.isConnected()) {
                store = session.getStore(protocol);
                store.connect(host, username, password);
            }

            // Close existing mailbox if open in wrong mode.
            if (mailbox != null && mailbox.isOpen()) {
                mailbox.close(false);
            }

            mailbox = store.getFolder(folder);
            if (mailbox == null || !mailbox.exists()) {
                log.error("Folder '{}' not found or does not exist", folder);
                return -1;
            }

            mailbox.open(Folder.READ_WRITE);
            Message[] messages = mailbox.getMessages();
            int count = messages != null ? messages.length : 0;

            if (count > 0) {
                for (Message message : messages) {
                    message.setFlag(Flags.Flag.DELETED, true);
                }
                mailbox.expunge();
                log.info("Purged {} message(s) from folder '{}'", count, folder);
            } else {
                log.info("Folder '{}' is already empty", folder);
            }

            return count;

        } catch (MessagingException me) {
            log.error("Failed to purge folder '{}': {}", folder, me.getMessage());
            return -1;
        }
    }

    /**
     * Extracts all MIME parts from a message using the built-in EmailParser.
     *
     * <p>Returned FileMimeParts remain readable until the caller closes each one.
     * The caller owns their temporary files, independently of this client.
     *
     * @param message The message to extract parts from.
     * @return List of MimePart objects, or empty list on error.
     */
    public List<MimePart> extractMimeParts(Message message) {
        if (message == null) {
            return Collections.emptyList();
        }

        try (EmailParser parser = parseMessage(message)) {
            return parser.detachParts();
        } catch (MessagingException | IOException e) {
            log.error("Error extracting MIME parts", e);
            return Collections.emptyList();
        }
    }

    private EmailParser parseMessage(Message message) throws MessagingException, IOException {
        Path source = Files.createTempFile("mime-message-", ".tmp");
        EmailParser parser = null;
        try {
            try (var output = Files.newOutputStream(source)) {
                message.writeTo(output);
            }
            parser = new EmailParser(source.toString());
            parser.parse();
            Files.delete(source);
            return parser;
        } catch (MessagingException | IOException | RuntimeException e) {
            if (parser != null) parser.close();
            try {
                Files.deleteIfExists(source);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Extracts parts from a message and converts them to PartDescriptor records.
     *
     * <p>This is a convenience method that returns parts as simple records with
     * headers as a Map, body as a String, and content hashes for assertion matching.
     *
     * @param message The message to extract parts from.
     * @return List of PartDescriptor objects, or empty list on error.
     */
    public List<PartDescriptor> extractParts(Message message) {
        if (message == null) return Collections.emptyList();
        List<PartDescriptor> descriptors = new ArrayList<>();
        try (EmailParser parser = parseMessage(message)) {
            for (MimePart part : parser.getParts()) {
                // Extract headers.
                Map<String, String> headers = new LinkedHashMap<>();
                for (MimeHeader header : part.getHeaders().get()) {
                    headers.put(header.getName(), header.getValue());
                }

                // Extract body content.
                String body = "";
                if (part instanceof TextMimePart textPart) {
                    body = textPart.getContent();
                } else if (part instanceof FileMimePart filePart) {
                    try (Reader reader = new java.io.InputStreamReader(
                            Files.newInputStream(filePart.getFile().toPath()), Charset.defaultCharset())) {
                        body = IOUtils.toString(reader);
                    }
                } else if (!(part instanceof MultipartMimePart)) {
                    body = new String(part.getBytes(), Charset.defaultCharset());
                }

                // Extract hashes.
                Map<String, String> hashes = new LinkedHashMap<>();
                String sha1 = part.getHash(HashType.SHA_1);
                String sha256 = part.getHash(HashType.SHA_256);
                String md5 = part.getHash(HashType.MD_5);
                if (sha1 != null) hashes.put("sha1", sha1);
                if (sha256 != null) hashes.put("sha256", sha256);
                if (md5 != null) hashes.put("md5", md5);

                descriptors.add(new PartDescriptor(headers, body, hashes, part.getSize()));
            }
            return descriptors;
        } catch (MessagingException | IOException e) {
            log.error("Error extracting MIME part descriptors", e);
            return Collections.emptyList();
        }
    }

    /**
     * Descriptor for a MIME part containing headers, body content, and hashes.
     *
     * @param headers Map of header names to values.
     * @param body    Decoded body content as a string.
     * @param hashes  Map of hash type (sha1, sha256, md5) to Base64-encoded hash value.
     * @param size    Size of the part content in bytes.
     */
    public record PartDescriptor(Map<String, String> headers, String body, Map<String, String> hashes, long size) {
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
