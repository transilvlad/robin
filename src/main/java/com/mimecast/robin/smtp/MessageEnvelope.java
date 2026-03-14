package com.mimecast.robin.smtp;

import com.mimecast.robin.config.assertion.AssertConfig;
import com.mimecast.robin.config.assertion.MimeConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.util.PathUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Message envelope.
 *
 * <p>This is the container for SMTP envelopes.
 * <p>It will store the metadata associated with each email sent.
 */
public class MessageEnvelope implements Serializable, Cloneable {
    @Serial
    private static final long serialVersionUID = 1L;

    // Set MAIL FROM and RCPT TO.
    private String mail = null;
    private String rcpt = null;
    private List<String> rcpts = new ArrayList<>();
    private final Map<String, List<String>> params = new HashMap<>();
    private final Map<String, String> headers = new HashMap<>();
    private boolean prependHeaders = false;

    // Blackholed status - true if email should be accepted but not saved.
    private boolean blackholed = false;

    // Bot addresses - recipients that matched bot patterns with their bot names
    private final Map<String, List<String>> botAddresses = new HashMap<>();

    // Set MimeConfig.
    private MimeConfig mime = null;

    // Set EML file, folder or null.
    private String file = null;
    private String folder = null;
    private MessageSource messageSource = null;

    // Set EML stream or null.
    private transient InputStream stream = null;
    // Used for serialisation.
    private byte[] bytes = null;

    // If EML is null set subject and message.
    private String subject = null;
    private String message = null;

    private final String date;
    private final String msgId;

    private int chunkSize = 0;
    private boolean chunkBdat = false;
    private boolean chunkWrite = false;

    private int terminateAfterBytes = 0;
    private boolean terminateBeforeDot = false;
    private boolean terminateAfterDot = false;

    private int slowBytes = 1;
    private int slowWait = 0;

    private int repeat = 0;

    // Assertions to be made against transaction.
    private AssertConfig assertConfig;

    // Scan results from security scanners (Rspamd, ClamAV, etc.)
    private List<Map<String, Object>> scanResults = Collections.synchronizedList(new ArrayList<>());

    /**
     * Constructs a new MessageEnvelope instance.
     */
    public MessageEnvelope() {
        date = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Config.getProperties().getLocale())
                .format(new Date());
        String now = String.valueOf(System.currentTimeMillis());
        String uid = UUID.randomUUID() + "-" + now;

        int size = 50 + 31 - date.length(); // Fixed length for unit tests stability.
        if (uid.length() >= size) {
            msgId = uid;
        } else {
            StringBuilder padded = new StringBuilder(size);
            for (int i = uid.length(); i < size; i++) {
                padded.append('0');
            }
            padded.append(uid);
            msgId = padded.toString();
        }
    }

    /**
     * Gets date.
     *
     * @return Date string.
     */
    public String getDate() {
        return date;
    }

    /**
     * Gets yyyyMMdd date.
     *
     * @return Date string.
     */
    public String getYymd() {
        return new SimpleDateFormat("yyyyMMdd").format(new Date());
    }

    /**
     * Gets current year.
     *
     * @return Year string.
     */
    public String getYear() {
        return new SimpleDateFormat("yyyy").format(new Date());
    }

    /**
     * Gets Message-ID.
     *
     * @return Message-ID string.
     */
    public String getMessageId() {
        return msgId;
    }

    /**
     * Gets MAIL FROM.
     *
     * @return MAIL FROM address.
     */
    public String getMailFrom() {
        if (StringUtils.isNotBlank(mail)) {
            return mail;
        }
        return "";
    }

    /**
     * Gets RCPT TO.
     *
     * @return RCPT TO address.
     */
    public String getRcptTo() {
        if (StringUtils.isNotBlank(rcpt)) {
            return rcpt;
        } else if (!rcpts.isEmpty()) {
            return rcpts.getFirst();
        }
        return "";
    }

    /**
     * Gets MAIL.
     *
     * @return MAIL address.
     */
    public String getMail() {
        return mail;
    }

    /**
     * Sets MAIL.
     *
     * @param mail MAIL address.
     * @return Self.
     */
    public MessageEnvelope setMail(String mail) {
        this.mail = mail;
        return this;
    }

    /**
     * Gets RCPT.
     *
     * @return RCPT address.
     */
    public String getRcpt() {
        return rcpt;
    }

    /**
     * Sets RCPT.
     *
     * @param rcpt RCPT address.
     * @return Self.
     */
    public MessageEnvelope setRcpt(String rcpt) {
        this.rcpt = rcpt;
        return this;
    }

    /**
     * Gets recipients addresses.
     *
     * @return Recipients address list.
     */
    public List<String> getRcpts() {
        if (rcpt != null && !rcpts.contains(rcpt)) {
            rcpts.add(rcpt);
        }

        return rcpts;
    }

    /**
     * Adds recipient address.
     *
     * @param rcpt Recipients address.
     * @return Self.
     */
    public MessageEnvelope addRcpt(String rcpt) {
        this.rcpts.add(rcpt);
        return this;
    }

    /**
     * Sets recipients addresses.
     *
     * @param rcpts Recipients address list.
     * @return Self.
     */
    public MessageEnvelope setRcpts(List<String> rcpts) {
        this.rcpts = rcpts;
        return this;
    }

    /**
     * Add SMTP parameters.
     *
     * @param extension SMTP extension to add parameter too.
     * @param param     Param and value if any.
     * @return Self.
     */
    public MessageEnvelope addParam(String extension, String param) {
        if (params.containsKey("extension")) {
            params.put(extension, Stream.concat(params.get(extension).stream(), Stream.of(param))
                    .collect(Collectors.toList()));
        } else {
            params.put(extension.toLowerCase(), Stream.of(param).collect(Collectors.toList()));
        }
        return this;
    }

    /**
     * Gets SMTP parameter by name.
     *
     * @param extension SMTP extension to get parameter for.
     * @return String.
     */
    public String getParams(String extension) {
        List<String> param = params.get(extension);
        return param != null && !param.isEmpty() ? " " + String.join(" ", param) : "";
    }

    /**
     * Adds magic header.
     *
     * @param name  Header name.
     * @param value Header value.
     * @return Self.
     */
    public MessageEnvelope addHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /**
     * Sets magic headers.
     *
     * @param headers Map of String, String.
     * @return Self.
     */
    public MessageEnvelope setHeaders(Map<String, String> headers) {
        this.headers.clear();
        this.headers.putAll(headers);
        return this;
    }

    /**
     * Gets magic headers.
     *
     * @return Map of String, String.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Is prepend headers.
     * <p>Auto prepend all magic headers to the email.
     *
     * @return Boolean.
     */
    public boolean isPrependHeaders() {
        return prependHeaders;
    }

    /**
     * Sets prepend headers.
     *
     * @param prependHeaders Boolean.
     * @return Self.
     */
    public MessageEnvelope setPrependHeaders(boolean prependHeaders) {
        this.prependHeaders = prependHeaders;
        return this;
    }

    /**
     * Gets path to eml file.
     *
     * @return File path.
     */
    public String getFile() {
        if (file != null) {
            return file;
        }
        if (messageSource instanceof FileMessageSource fileSource && fileSource.getPath() != null) {
            return fileSource.getPath().toString();
        }
        return null;
    }

    /**
     * Sets path to eml file.
     *
     * @param file File path.
     * @return Self.
     */
    public MessageEnvelope setFile(String file) {
        this.file = file;
        if (StringUtils.isBlank(file)) {
            if (messageSource instanceof FileMessageSource) {
                this.messageSource = null;
            }
            return this;
        }

        Path filePath = Path.of(file);
        if (Files.exists(filePath) || this.messageSource == null) {
            this.bytes = null;
            this.stream = null;
            this.messageSource = new FileMessageSource(filePath);
        }
        return this;
    }

    /**
     * Gets path to folder.
     *
     * @return folder path.
     */
    public String getFolder() {
        return folder;
    }

    /**
     * Sets path to folder.
     *
     * @param folder Folder path.
     * @return Self.
     */
    public MessageEnvelope setFolder(String folder) {
        this.folder = folder;
        return this;
    }

    /**
     * Gets file path to eml file from folder contents if any.
     *
     * @return Folder path.
     */
    public String getFolderFile() {
        return PathUtils.folderFile(getFolder(), Collections.singletonList("eml"));
    }

    /**
     * Gets MimeConfig.
     *
     * @return MimeConfig instance.
     */
    public MimeConfig getMime() {
        return mime;
    }

    /**
     * Sets MimeConfig.
     *
     * @param mime MimeConfig instance.
     * @return Self.
     */
    public MessageEnvelope setMime(MimeConfig mime) {
        this.mime = mime;
        return this;
    }

    /**
     * Gets eml stream.
     *
     * @return Eml stream.
     */
    public InputStream getStream() {
        return stream != null ? stream : (bytes != null ? new ByteArrayInputStream(bytes) : null);
    }

    /**
     * Sets eml stream.
     *
     * @param stream Eml stream.
     * @return Self.
     */
    public MessageEnvelope setStream(InputStream stream) {
        this.stream = stream;
        this.bytes = null;
        this.messageSource = null;
        return this;
    }

    /**
     * Sets eml byte array.
     *
     * @param bytes Eml byte array.
     * @return Self.
     */
    public MessageEnvelope setBytes(byte[] bytes) {
        this.bytes = bytes;
        this.stream = null;
        this.messageSource = bytes != null ? new InMemoryMessageSource(bytes) : null;
        return this;
    }

    /**
     * Sets canonical message source.
     *
     * @param messageSource Message source.
     * @return Self.
     */
    public MessageEnvelope setMessageSource(MessageSource messageSource) {
        this.messageSource = messageSource;
        this.stream = null;
        if (messageSource instanceof InMemoryMessageSource inMemory) {
            this.bytes = inMemory.readAllBytes();
        } else {
            this.bytes = null;
        }
        if (messageSource instanceof FileMessageSource fileSource && fileSource.getPath() != null) {
            this.file = fileSource.getPath().toString();
        }
        return this;
    }

    /**
     * Gets canonical message source.
     *
     * @return MessageSource or null.
     */
    public MessageSource getMessageSource() {
        return messageSource;
    }

    /**
     * Checks if the envelope has a readable message source.
     *
     * @return Boolean.
     */
    public boolean hasMessageSource() {
        return messageSource != null || stream != null || bytes != null || StringUtils.isNotBlank(file);
    }

    /**
     * Opens a fresh input stream for the message contents.
     *
     * @return InputStream instance.
     * @throws IOException On I/O error.
     */
    public InputStream openMessageStream() throws IOException {
        if (messageSource != null) {
            return messageSource.openStream();
        }
        if (stream != null) {
            return stream;
        }
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        if (StringUtils.isNotBlank(file)) {
            return Files.newInputStream(Path.of(file));
        }
        return null;
    }

    /**
     * Reads the full message payload into memory.
     *
     * @return Message bytes.
     * @throws IOException On I/O error.
     */
    public byte[] readMessageBytes() throws IOException {
        if (messageSource != null) {
            return messageSource.readAllBytes();
        }
        if (bytes != null) {
            return Arrays.copyOf(bytes, bytes.length);
        }
        if (stream != null) {
            return stream.readAllBytes();
        }
        if (StringUtils.isNotBlank(file)) {
            return Files.readAllBytes(Path.of(file));
        }
        return new byte[0];
    }

    /**
     * Checks if the envelope already has a materialized file on disk.
     *
     * @return Boolean.
     */
    public boolean hasMaterializedFile() {
        return StringUtils.isNotBlank(file) && Files.exists(Path.of(file));
    }

    /**
     * Materializes the message to the current file path or to a generated temp file.
     *
     * @return Materialized file path or null if no source exists.
     * @throws IOException On I/O error.
     */
    public String materializeMessageFile() throws IOException {
        if (hasMaterializedFile()) {
            return file;
        }

        Path target;
        if (StringUtils.isNotBlank(file)) {
            target = Path.of(file);
        } else {
            target = Files.createTempFile("robin-envelope-", ".eml");
        }

        return materializeMessageFile(target).toString();
    }

    /**
     * Materializes the message to a specific target file.
     *
     * @param target Target file.
     * @return Materialized file path.
     * @throws IOException On I/O error.
     */
    public Path materializeMessageFile(Path target) throws IOException {
        if (messageSource != null) {
            Path materialized = messageSource.materialize(target);
            this.file = materialized.toString();
            this.messageSource = new FileMessageSource(materialized);
            this.stream = null;
            this.bytes = null;
            return materialized;
        }

        if (bytes != null) {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes);
            this.file = target.toString();
            this.messageSource = new FileMessageSource(target);
            this.bytes = null;
            return target;
        }

        if (stream != null) {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream input = stream) {
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            this.file = target.toString();
            this.messageSource = new FileMessageSource(target);
            this.stream = null;
            return target;
        }

        if (StringUtils.isNotBlank(file) && Files.exists(Path.of(file))) {
            this.messageSource = new FileMessageSource(Path.of(file));
            return Path.of(file);
        }

        throw new IOException("No message source available");
    }

    /**
     * Gets subject.
     *
     * @return Subject string.
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Sets subject.
     * <p>May be used if no eml file provided.
     * <p>Basic plain/text eml will be generated.
     *
     * @param subject Subject string.
     * @return Self.
     */
    public MessageEnvelope setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    /**
     * Gets message.
     * <p>May be used if no eml file provided.
     * <p>Basic plain/text eml will be generated.
     *
     * @return Message string.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets email body.
     *
     * @param message Body string.
     * @return Self.
     */
    public MessageEnvelope setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Builds email headers.
     *
     * @return Email headers string.
     */
    public String buildHeaders() {
        String to = "<" + String.join(">, <", getRcpts()) + ">";

        return "MIME-Version: 1.0\r\n" +
                "Message-ID: <" + msgId + mail + ">\r\n" +
                "Date: " + date + "\r\n" +
                "From: <" + mail + ">\r\n" +
                "To: " + to + "\r\n" +
                "Subject: " + (subject != null ? subject : "") + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n";
    }

    /**
     * Gets chunk size.
     * <p>Size of how many bytes to write to the socket in one write.
     *
     * @return Chunk size in bytes.
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Sets chunk size.
     *
     * @param chunkSize Chunk size.
     * @return Self.
     */
    public MessageEnvelope setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        return this;
    }

    /**
     * Is chunk BDAT command.
     * <p>This makes the client write the BDAT command with the first chunk of the message.
     * <p>This can uncover accidental buffer clearing when switching from SMTP to MIME mode.
     *
     * @return Boolean.
     */
    public boolean isChunkBdat() {
        return chunkBdat;
    }

    /**
     * Sets chunk BDAT command.
     *
     * @param chunkBdat Boolean.
     * @return Self.
     */
    public MessageEnvelope setChunkBdat(boolean chunkBdat) {
        this.chunkBdat = chunkBdat;
        return this;
    }

    /**
     * Is chunk write randomly.
     * <p>This will ignore chunk size and just write random size chunks.
     * <p>The chunks are limite to in between 1024 and 2048 bytes.
     *
     * @return Boolean.
     */
    public boolean isChunkWrite() {
        return chunkWrite;
    }

    /**
     * Sets chunk write.
     *
     * @param chunkWrite Boolean.
     * @return Self.
     */
    public MessageEnvelope setChunkWrite(boolean chunkWrite) {
        this.chunkWrite = chunkWrite;
        return this;
    }

    /**
     * Gets terminate after bytes.
     * <p>Size of how many bytes to write to the socket before terminating connection.
     *
     * @return Size in bytes.
     */
    public int getTerminateAfterBytes() {
        return terminateAfterBytes;
    }

    /**
     * Sets chunk size.
     *
     * @param terminateAfterBytes Size in bytes.
     * @return Self.
     */
    public MessageEnvelope setTerminateAfterBytes(int terminateAfterBytes) {
        this.terminateAfterBytes = terminateAfterBytes;
        return this;
    }

    /**
     * Is terminate before dot.
     * <p>Terminate connection before transmitting the &lt;CRLF&gt;.&lt;CRLF&gt; termiantor.
     *
     * @return Boolean.
     */
    public boolean isTerminateBeforeDot() {
        return terminateBeforeDot;
    }

    /**
     * Sets terminate after dot.
     *
     * @param terminateBeforeDot Boolean.
     * @return Self.
     */
    public MessageEnvelope setTerminateBeforeDot(boolean terminateBeforeDot) {
        this.terminateBeforeDot = terminateBeforeDot;
        return this;
    }

    /**
     * Is terminate after dot.
     * <p>Terminate connection after transmitting the &lt;CRLF&gt;.&lt;CRLF&gt; termiantor.
     *
     * @return Boolean.
     */
    public boolean isTerminateAfterDot() {
        return terminateAfterDot;
    }

    /**
     * Sets terminate after dot.
     *
     * @param terminateAfterDot Boolean.
     * @return Self.
     */
    public MessageEnvelope setTerminateAfterDot(boolean terminateAfterDot) {
        this.terminateAfterDot = terminateAfterDot;
        return this;
    }

    /**
     * Gets slow bytes.
     * <p>This adds a write delay every given number of bytes.
     * <p>Must be &gt;= 128 or the functionality will be disabled.
     * <p>Works only with file and stream not with headers and message.
     *
     * @return Size in bytes.
     */
    public int getSlowBytes() {
        return slowBytes;
    }

    /**
     * Sets slow bytes.
     *
     * @param slowBytes Chunk size.
     * @return Self.
     */
    public MessageEnvelope setSlowBytes(int slowBytes) {
        this.slowBytes = slowBytes;
        return this;
    }

    /**
     * Gets slow wait.
     * <p>Wait time in miliseconds.
     * <p>Must be &gt;= 100 or the functionality will be disabled.
     *
     * @return Chunk size in bytes.
     */
    public int getSlowWait() {
        return slowWait;
    }

    /**
     * Sets slow wait.
     *
     * @param slowWait Slow wait.
     * @return Self.
     */
    public MessageEnvelope setSlowWait(int slowWait) {
        this.slowWait = slowWait;
        return this;
    }

    /**
     * Gets repeat times.
     * <p>Send the same envelope this many times +1.
     *
     * @return Repeat times.
     */
    public int getRepeat() {
        return repeat;
    }

    /**
     * Sets repeat times.
     *
     * @param repeat Repeat times.
     * @return Self.
     */
    public MessageEnvelope setRepeat(int repeat) {
        this.repeat = repeat;
        return this;
    }

    /**
     * Gets AssertConfig.
     *
     * @return AssertConfig instance.
     */
    public AssertConfig getAssertions() {
        return assertConfig;
    }

    /**
     * Sets AssertConfig.
     *
     * @param assertConfig AssertConfig instance.
     * @return MessageEnvelope instance.
     */
    public MessageEnvelope setAssertions(AssertConfig assertConfig) {
        this.assertConfig = assertConfig;
        return this;
    }

    /**
     * Is envelope blackholed.
     *
     * @return Boolean.
     */
    public boolean isBlackholed() {
        return blackholed;
    }

    /**
     * Sets envelope blackholed status.
     *
     * @param blackholed Blackholed status.
     * @return MessageEnvelope instance.
     */
    public MessageEnvelope setBlackholed(boolean blackholed) {
        this.blackholed = blackholed;
        return this;
    }

    /**
     * Gets scan results from security scanners.
     * <p>This list is thread-safe and contains scan results from various security scanners
     * such as Rspamd (spam/phishing) and ClamAV (virus scanning).
     *
     * @return Unmodifiable view of the scan results list.
     */
    public List<Map<String, Object>> getScanResults() {
        return Collections.unmodifiableList(scanResults);
    }

    /**
     * Adds a scan result to the list.
     * <p>This method is thread-safe.
     *
     * @param scanResult The scan result to add.
     * @return MessageEnvelope instance.
     */
    public MessageEnvelope addScanResult(Map<String, Object> scanResult) {
        if (scanResult != null && !scanResult.isEmpty()) {
            this.scanResults.add(scanResult);
        }
        return this;
    }

    /**
     * Adds a bot address with its associated bot name.
     *
     * @param address Email address that matched a bot pattern.
     * @param botName Name of the bot to process this address.
     * @return MessageEnvelope instance.
     */
    public MessageEnvelope addBotAddress(String address, String botName) {
        if (address != null && !address.isEmpty() && botName != null && !botName.isEmpty()) {
            botAddresses.computeIfAbsent(address, k -> new ArrayList<>()).add(botName);
        }
        return this;
    }

    /**
     * Gets the map of bot addresses to bot names.
     *
     * @return Unmodifiable map of bot addresses.
     */
    public Map<String, List<String>> getBotAddresses() {
        return Collections.unmodifiableMap(botAddresses);
    }

    /**
     * Checks if the given address is a bot address.
     *
     * @param address Email address to check.
     * @return true if address is a bot address.
     */
    public boolean isBotAddress(String address) {
        return address != null && botAddresses.containsKey(address);
    }

    /**
     * Checks if this envelope has any bot addresses.
     *
     * @return true if envelope has bot addresses.
     */
    public boolean hasBotAddresses() {
        return !botAddresses.isEmpty();
    }

    /**
     * Creates a copy of this MessageEnvelope.
     * <p>Creates a new instance with all fields copied from this envelope.
     * <p>Note: date and msgId from the original are preserved in the clone.
     * <p>Collections and arrays are deep copied to ensure proper isolation.
     *
     * @return A cloned MessageEnvelope instance.
     */
    @Override
    public MessageEnvelope clone() {
        try {
            MessageEnvelope cloned = (MessageEnvelope) super.clone();

            // Deep copy mutable collections.
            cloned.rcpts = new ArrayList<>(this.rcpts);

            cloned.params.clear();
            for (Map.Entry<String, List<String>> entry : this.params.entrySet()) {
                cloned.params.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }

            cloned.headers.clear();
            cloned.headers.putAll(this.headers);

            // Deep copy botAddresses
            cloned.botAddresses.clear();
            for (Map.Entry<String, List<String>> entry : this.botAddresses.entrySet()) {
                cloned.botAddresses.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }

            // Deep copy scanResults
            cloned.scanResults = Collections.synchronizedList(new ArrayList<>());
            synchronized (this.scanResults) {
                for (Map<String, Object> scanResult : this.scanResults) {
                    Map<String, Object> clonedResult = new HashMap<>();
                    for (Map.Entry<String, Object> entry : scanResult.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Map) {
                            // Shallow copy of nested map
                            clonedResult.put(entry.getKey(), new HashMap<>((Map<?, ?>) value));
                        } else if (value instanceof Collection) {
                            // Shallow copy of nested collection
                            clonedResult.put(entry.getKey(), new ArrayList<>((Collection<?>) value));
                        } else {
                            clonedResult.put(entry.getKey(), value);
                        }
                    }
                    cloned.scanResults.add(clonedResult);
                }
            }

            // Deep copy byte array if present.
            if (this.bytes != null) {
                cloned.bytes = Arrays.copyOf(this.bytes, this.bytes.length);
            }

            // For reference-counted sources, acquire a new reference and share the same instance.
            // This allows multiple consumers (main thread + bot threads) to access the file,
            // with automatic cleanup when all consumers have released their references.
            if (this.messageSource instanceof RefCountedFileMessageSource refCounted) {
                refCounted.acquire();
                cloned.messageSource = refCounted;
            } else if (this.messageSource instanceof InMemoryMessageSource inMemory) {
                cloned.messageSource = new InMemoryMessageSource(inMemory.readAllBytes());
            } else if (this.messageSource instanceof FileMessageSource fileSource && fileSource.getPath() != null) {
                cloned.messageSource = new FileMessageSource(fileSource.getPath());
            } else {
                cloned.messageSource = this.messageSource;
            }

            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }
}
