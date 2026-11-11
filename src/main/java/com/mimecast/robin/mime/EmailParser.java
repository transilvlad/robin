package com.mimecast.robin.mime;

import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.headers.MimeHeaders;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.MultipartMimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.smtp.io.LineInputStream;
import com.mimecast.robin.util.QuotedPrintableDecoder;
import com.mimecast.robin.util.UuencodeDecoder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * EmailParser is a standalone MIME email parser that extracts email headers, body content,
 * and attachments from RFC 2822 formatted email messages.
 * <p>
 * This parser handles:
 * <ul>
 *     <li>Multi-line headers with proper folding support</li>
 *     <li>Single and multipart MIME messages</li>
 *     <li>Various content encodings (Base64, Quoted-Printable, plain text)</li>
 *     <li>Nested multipart structures and embedded messages (message/rfc822)</li>
 *     <li>Attachment detection and classification by MIME type</li>
 *     <li>Content hashing (SHA-1, SHA-256, MD5) for integrity verification</li>
 * </ul>
 * <p>
 * The parser is designed as a reusable library for any Java application needing to parse
 * email messages programmatically. It can be integrated into MTA implementations, email
 * processing pipelines, forensic tools, or testing frameworks.
 * <p>
 * This class implements AutoCloseable to ensure proper cleanup of temporary files created
 * for non-text MIME parts. The close() method deletes temporary part files but not the main
 * email file.
 * <p>
 * Example usage:
 * <pre>
 * try (EmailParser parser = new EmailParser("/path/to/email.eml")) {
 *     parser.parse();
 *     MimeHeaders headers = parser.getHeaders();
 *     List&lt;MimePart&gt; parts = parser.getParts();
 * }
 * </pre>
 *
 * @see MimeHeaders
 * @see MimePart
 * @see LineInputStream
 */
public class EmailParser implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(EmailParser.class);

    /**
     * Maximum nesting depth for multipart and message/rfc822 parts.
     * <p>Bounds recursive descent so a maliciously (or accidentally) deeply nested message
     * cannot exhaust the call stack. Structure beyond this depth is kept as an opaque part
     * rather than expanded further.
     */
    private static final int MAX_NESTING_DEPTH = 10;

    /**
     * Checks whether a line is a MIME part boundary delimiter, per RFC 2046: "--" followed by
     * the boundary value at column zero, with optional trailing SP/TAB.
     * <p>Deliberately exact rather than a substring check, so body content that merely mentions
     * the boundary token cannot be mistaken for a real delimiter and desynchronize parsing.
     *
     * @param line     Raw line, including its line terminator.
     * @param boundary MIME boundary value (without the leading "--"), may be null/empty.
     * @return true if the line is the mid-part delimiter "--boundary".
     */
    private static boolean isBoundaryLine(String line, String boundary) {
        return matchesBoundary(line, boundary, false);
    }

    /**
     * Checks whether a line is the final MIME part boundary delimiter "--boundary--".
     *
     * @param line     Raw line, including its line terminator.
     * @param boundary MIME boundary value (without the leading "--"), may be null/empty.
     * @return true if the line is the closing delimiter "--boundary--".
     * @see #isBoundaryLine(String, String)
     */
    private static boolean isEndBoundaryLine(String line, String boundary) {
        return matchesBoundary(line, boundary, true);
    }

    private static String withoutLineEnding(String line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\n') end--;
        if (end > 0 && line.charAt(end - 1) == '\r') end--;
        return line.substring(0, end);
    }

    private static boolean matchesBoundary(String line, String boundary, boolean closing) {
        if (boundary == null || boundary.isEmpty()) return false;
        String value = withoutLineEnding(line);
        String delimiter = "--" + boundary + (closing ? "--" : "");
        if (!value.startsWith(delimiter)) return false;
        for (int i = delimiter.length(); i < value.length(); i++) {
            if (value.charAt(i) != ' ' && value.charAt(i) != '\t') return false;
        }
        return true;
    }

    /**
     * Email input stream with line-based reading and pushback buffer support.
     * Maintains position for boundary detection and part parsing.
     */
    private final LineInputStream stream;

    /**
     * Container for all email headers parsed from the message header section.
     * Supports multi-line headers and parameter extraction.
     */
    private final MimeHeaders headers = new MimeHeaders();

    /**
     * List of parsed MIME parts extracted from the email body.
     * Includes both inline content and attachments, maintaining structure and metadata.
     */
    private final List<MimePart> parts = new ArrayList<>();
    private final List<FileMimePart> ownedParts = new ArrayList<>();
    private boolean closed;
    private byte[] pendingLine;
    private final List<String> activeBoundaries = new ArrayList<>();

    private byte[] readLine() throws IOException {
        if (pendingLine == null) return stream.readLine();
        byte[] line = pendingLine;
        pendingLine = null;
        return line;
    }

    private boolean atBoundary(String line) {
        for (String boundary : activeBoundaries) {
            if (isBoundaryLine(line, boundary) || isEndBoundaryLine(line, boundary)) return true;
        }
        return false;
    }

    /**
     * Whether this single-pass parser has completed parsing.
     */
    private boolean parsed = false;

    /**
     * Whether the completed parse stopped after the message headers.
     */
    private boolean parsedHeadersOnly = false;

    /**
     * Constructs a new EmailParser instance from a file path.
     * <p>
     * Uses a default pushback buffer size of 1024 bytes, which is sufficient for most
     * email messages. The pushback buffer is used for boundary detection and backtracking
     * when reading multipart boundaries.
     *
     * @param path Path to the email file (.eml format)
     * @throws FileNotFoundException If the email file does not exist or cannot be opened
     * @see #EmailParser(String, int)
     */
    public EmailParser(String path) throws FileNotFoundException {
        this(path, 1024);
    }

    /**
     * Constructs a new EmailParser instance from a file path with custom buffer size.
     * <p>
     * Allows customization of the pushback buffer size. Use a larger buffer if parsing
     * very complex multipart messages with large boundaries, or a smaller buffer for
     * memory-constrained environments.
     *
     * @param path Path to the email file (.eml format)
     * @param size Pushback buffer size in bytes. Recommended minimum: 512, default: 1024
     * @throws FileNotFoundException If the email file does not exist or cannot be opened
     * @see #EmailParser(LineInputStream)
     */
    public EmailParser(String path, int size) throws FileNotFoundException {
        this.stream = new LineInputStream(new FileInputStream(path), size);
    }

    /**
     * Constructs a new EmailParser instance from an input stream.
     *
     * @param inputStream Email input stream.
     */
    public EmailParser(InputStream inputStream) {
        this(inputStream, 1024);
    }

    /**
     * Constructs a new EmailParser instance from an input stream with custom buffer size.
     *
     * @param inputStream Email input stream.
     * @param size        Pushback buffer size.
     */
    public EmailParser(InputStream inputStream, int size) {
        this.stream = new LineInputStream(inputStream, size);
    }

    /**
     * Constructs a new EmailParser instance from an existing LineInputStream.
     * <p>
     * Use this constructor for advanced scenarios such as parsing email content from
     * network streams, memory buffers, or other custom input sources. The parser
     * closes the supplied stream when parsing completes or the parser is closed.
     *
     * @param stream A pre-configured LineInputStream for reading email content
     * @see LineInputStream
     */
    public EmailParser(LineInputStream stream) {
        this.stream = stream;
    }

    /**
     * Gets the parsed email headers.
     * <p>
     * The returned MimeHeaders object contains all headers from the email message,
     * including standard headers (From, To, Subject, Date, etc.) and custom headers.
     * Multi-line headers are properly unfolded and available for parameter extraction.
     *
     * @return MimeHeaders instance containing all parsed headers
     * @see MimeHeaders
     */
    public MimeHeaders getHeaders() {
        return headers;
    }

    /**
     * Gets the parsed MIME parts from the email body.
     * <p>
     * Returns a list of all MIME parts extracted from the email, including:
     * <ul>
     *     <li>Text parts (plain text, HTML)</li>
     *     <li>Attachments and embedded files</li>
     *     <li>Related parts (inline images, stylesheets)</li>
     *     <li>Nested multipart structures</li>
     * </ul>
     * Each part includes metadata such as content type, encoding, size, and content hashes.
     *
     * @return Borrowed list of parts; file parts remain owned by this parser until detached.
     * @see MimePart
     */
    public List<MimePart> getParts() {
        return parts;
    }

    /**
     * Transfers ownership of fully parsed parts to the caller. The caller must close
     * each returned FileMimePart; closing this parser no longer releases those parts.
     *
     * @return Independent list of parts, empty after a previous transfer.
     * @throws IllegalStateException If full parsing has not completed or this parser is closed.
     */
    public synchronized List<MimePart> detachParts() {
        if (closed || !parsed || parsedHeadersOnly) {
            throw new IllegalStateException("Parts can only be detached from a completed, open full parser");
        }
        List<MimePart> detached = new ArrayList<>(parts);
        parts.clear();
        ownedParts.removeAll(detached);
        return detached;
    }

    /**
     * Parses the complete email message including headers and body.
     * <p>
     * This is a convenience method equivalent to calling {@code parse(false)}.
     * After parsing completes, the underlying stream is automatically closed.
     * Use this method for standard email parsing workflows.
     *
     * @return Self for method chaining
     * @throws IOException If an error occurs while reading the email file
     * @see #parse(boolean)
     */
    public EmailParser parse() throws IOException {
        return parse(false);
    }

    /**
     * Parses the email with optional header-only mode.
     * <p>
     * Set {@code headersOnly} to true to parse only the email headers without reading
     * the body. This is useful for quick header extraction or when full message parsing
     * is not needed. The underlying stream is automatically closed after parsing.
     * <p>
     * Parsing is a single-pass operation. Repeating the same parse, or requesting
     * headers-only after a full parse, returns the finalized parser unchanged. A
     * headers-only parser cannot later be upgraded to a full parse because its stream
     * has already been consumed and closed.
     *
     * @param headersOnly If true, only headers are parsed; if false, headers and body are parsed
     * @return Self for method chaining
     * @throws IOException If an error occurs while reading the email file
     */
    public synchronized EmailParser parse(boolean headersOnly) throws IOException {
        if (closed) {
            throw new IllegalStateException("EmailParser is closed");
        }
        if (parsed) {
            if (headersOnly || !parsedHeadersOnly) {
                return this;
            }
            throw new IllegalStateException(
                    "Cannot fully parse an EmailParser that was already parsed in headers-only mode");
        }

        return parse(headersOnly, 0);
    }

    /**
     * Parses the email with optional header-only mode, tracking multipart/message nesting depth.
     *
     * @param headersOnly If true, only headers are parsed; if false, headers and body are parsed
     * @param depth       Current nesting depth, incremented across both nested multipart parts
     *                    and nested message/rfc822 parts. See {@link #MAX_NESTING_DEPTH}.
     * @return Self for method chaining
     * @throws IOException If an error occurs while reading the email file
     */
    private EmailParser parse(boolean headersOnly, int depth) throws IOException {
        try (stream) {
            parseHeaders();
            if (!headersOnly) {
                parseBody(depth);
            }
            parsed = true;
            parsedHeadersOnly = headersOnly;
            return this;
        } catch (IOException | RuntimeException e) {
            parsed = false;
            close();
            throw e;
        }
    }

    /**
     * Parses email headers from the input stream.
     * <p>
     * Reads and processes all headers up to the blank line that separates headers from body.
     * Properly handles RFC 2822 header folding (multi-line headers). The method stores
     * parsed headers in the headers collection using MimeHeader objects that support
     * parameter extraction and value manipulation.
     *
     * @throws IOException If an error occurs while reading from the stream
     */
    private void parseHeaders() throws IOException {
        readHeaders(headers);
    }

    private boolean readHeaders(MimeHeaders target) throws IOException {
        HeaderFraming framing = new HeaderFraming();
        byte[] bytes;
        StringBuilder header = new StringBuilder();
        while ((bytes = readLine()) != null) {
            framing.accept(bytes, 0, bytes.length);
            // The general line reader also terminates on bare CR. It is never a header EOL.
            if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') framing.finish();
            String line = new String(bytes);
            if (atBoundary(line)) {
                pendingLine = bytes;
                if (!header.isEmpty()) putIfValid(target, header.toString());
                log.warn("MIME part ended before its header/body separator");
                return false;
            }
            if (withoutLineEnding(line).isEmpty()) {
                if (!header.isEmpty()) putIfValid(target, header.toString());
                return true;
            }
            if (bytes[0] != ' ' && bytes[0] != '\t' && !header.isEmpty()) {
                putIfValid(target, header.toString());
                header.setLength(0);
            }
            header.append(line);
        }
        framing.finish();
        if (!header.isEmpty()) putIfValid(target, header.toString());
        return false;
    }

    /**
     * Whether parsing completed with valid raw header framing, not semantic RFC validation.
     * Partial headers after a parse failure are diagnostic evidence only and must not
     * be used to authorize an identity.
     *
     * @return True after a successful headers-only or full parse.
     */
    public boolean hasValidHeaders() {
        return parsed;
    }

    /**
     * Constructs a MimeHeader from a raw header line and adds it to the given collection,
     * discarding it instead if its name doesn't look like a valid RFC 5322 field name.
     * <p>Guards against malformed or attacker-crafted lines (e.g. plain body text containing a
     * colon) being captured as an unexpected header.
     *
     * @param headers   Collection to add the header to.
     * @param rawHeader Raw, unfolded header text ("Name: value").
     * @see MimeHeader#isValid()
     */
    private static void putIfValid(MimeHeaders headers, String rawHeader) {
        MimeHeader header = new MimeHeader(rawHeader);
        if (header.isValid()) {
            headers.put(header);
        } else {
            log.debug("Discarding line with invalid header name: {}", header.getName());
        }
    }

    /**
     * Parses email body to extract MIME parts.
     * <p>
     * Analyzes the Content-Type header to determine if the message is multipart.
     * For multipart messages, extracts the boundary parameter and recursively parses
     * each part. For single-part messages, reads the body content and processes encoding.
     * Supports nested multipart structures and embedded messages (message/rfc822).
     *
     * @param depth Current nesting depth. See {@link #MAX_NESTING_DEPTH}.
     * @throws IOException If an error occurs while reading from the stream
     */
    private void parseBody(int depth) throws IOException {
        MimeHeaders partHeaders = new MimeHeaders();
        headers.startsWith("content-").forEach(partHeaders::put);
        partHeaders.get().forEach(headers::remove);
        if (partHeaders.get("Content-Type").isEmpty()) {
            partHeaders.put(new MimeHeader("Content-Type", "text/plain"));
        }
        parseEntity(partHeaders, null, depth, false);
    }

    /**
     * Determines the filename for a MIME part based on headers.
     * <p>
     * Attempts to extract filename from Content-Disposition header first, then
     * falls back to Content-Type header. If no explicit filename is present, generates
     * a filename based on MIME type and part index. This ensures all parts can be
     * uniquely identified and extracted to the filesystem.
     *
     * @param headers MimeHeaders instance containing part headers
     * @return Filename string, or empty string if unable to determine
     */
    String getFileName(MimeHeaders headers) {
        String supplied = headers.getFileName();
        if (supplied != null) return supplied;

        Optional<MimeHeader> optional = headers.get("Content-Type");
        if (optional.isPresent()) {
            MimeHeader header = optional.get();

            String type = header.getCleanValue();
            if (type.equalsIgnoreCase("text/html")) {
                return "part." + parts.size() + ".html";

            } else if (type.equalsIgnoreCase("text/plain")) {
                return "part." + parts.size() + ".txt";

            } else if (type.equalsIgnoreCase("text/calendar")) {
                return "part." + parts.size() + ".cal";

            } else if (type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return "part." + parts.size() + ".img";

            } else if (type.toLowerCase(Locale.ROOT).startsWith("message/")) {
                return "rfc822." + parts.size() + ".eml";

            } else {
                return "part." + parts.size() + ".dat";
            }

        }

        return "";
    }

    /**
     * Parses individual MIME parts within a multipart message.
     * <p>
     * Reads and processes parts separated by the specified boundary string.
     * Handles nested multipart structures, embedded RFC 822 messages, and various
     * content encodings. Each part's headers are parsed and content is decoded according
     * to Content-Transfer-Encoding specification. Supports recursion for deeply nested
     * multipart structures.
     *
     * @param boundary MIME boundary string that separates parts
     * @param depth    Current nesting depth. See {@link #MAX_NESTING_DEPTH}.
     * @throws IOException If an error occurs while reading from the stream
     */
    private void parsePart(String boundary, int depth, boolean digest) throws IOException {
        activeBoundaries.add(boundary);
        try {
            byte[] bytes;
            while ((bytes = readLine()) != null) {
                String line = new String(bytes);
                if (isEndBoundaryLine(line, boundary)) return;
                if (isBoundaryLine(line, boundary)) {
                    MimeHeaders partHeaders = new MimeHeaders();
                    if (!readHeaders(partHeaders)) continue;
                    boolean implicitMessage = digest && partHeaders.get("Content-Type").isEmpty();
                    if (partHeaders.get("Content-Type").isEmpty()) {
                        partHeaders.put(new MimeHeader("Content-Type", implicitMessage ? "message/rfc822" : "text/plain"));
                    }
                    parseEntity(partHeaders, boundary, depth, implicitMessage);
                } else if (atBoundary(line)) {
                    pendingLine = bytes;
                    log.warn("Enclosing MIME boundary encountered before nested multipart closed");
                    return;
                }
            }
            log.warn("Multipart ended without its closing MIME boundary");
        } finally {
            activeBoundaries.removeLast();
        }
    }

    private void parseEntity(MimeHeaders partHeaders, String enclosingBoundary, int depth,
                             boolean implicitMessage) throws IOException {
        MimeHeader ct = partHeaders.get("Content-Type").orElseThrow();
        String type = ct.getCleanValue().toLowerCase(Locale.ROOT);
        String boundary = ct.getParameter("boundary");
        boolean multipart = type.startsWith("multipart/");
        boolean message = type.equals("message/rfc822");
        boolean withinDepthLimit = depth < MAX_NESTING_DEPTH;
        if (multipart && withinDepthLimit && boundary != null && !boundary.isEmpty()) {
            MimePart part = new MultipartMimePart();
            part.setHeaders(partHeaders);
            parts.add(part);
            parsePart(boundary, depth + 1, type.equals("multipart/digest"));
            return;
        }
        if (multipart || (message && !withinDepthLimit)) {
            log.warn("MIME structure is missing a boundary or exceeds nesting limit; retaining opaque content");
        }
        MimePart part = parsePartContent(type.startsWith("text/") || (type.startsWith("message/") && !message),
                partHeaders, enclosingBoundary);
        part.setHeaders(partHeaders);
        if (implicitMessage && partHeaders.get("Content-Disposition").isEmpty()
                && partHeaders.get("Content-ID").isEmpty() && onlyFraming(part)) {
            ((FileMimePart) part).close();
            ownedParts.remove(part);
            return;
        }
        parts.add(part);
        if (message && enclosingBoundary != null && withinDepthLimit && part.getSize() > 0) {
            FileMimePart container = (FileMimePart) part;
            try (EmailParser child = new EmailParser(new FileInputStream(container.getFile()))) {
                child.parse(false, depth + 1);
                for (MimePart descendant : child.detachParts()) {
                    parts.add(descendant);
                    if (descendant instanceof FileMimePart file) ownedParts.add(file);
                }
            }
        }
    }

    private boolean onlyFraming(MimePart part) throws IOException {
        try (InputStream input = new FileInputStream(((FileMimePart) part).getFile())) {
            int value;
            while ((value = input.read()) != -1) {
                if (value != '\r' && value != '\n') return false;
            }
            return true;
        }
    }

    /**
     * Parses and decodes MIME part content with integrity verification.
     * <p>
     * Reads the raw content of a MIME part up to the boundary marker (if present).
     * Automatically detects and applies content transfer encoding:
     * <ul>
     *     <li>Base64: Decodes from Base64 to binary</li>
     *     <li>Quoted-Printable: Decodes from Quoted-Printable to binary</li>
     *     <li>7bit/8bit/binary: Reads as-is without transformation</li>
     * </ul>
     * For each part, calculates cryptographic hashes (SHA-1, SHA-256, MD5) on the
     * decoded content for integrity verification and forensic purposes.
     *
     * @param isTextPart Boolean indicating if this is a text/* MIME type (affects part creation)
     * @param headers MimeHeaders instance containing Content-Transfer-Encoding and other metadata
     * @param boundary Optional MIME boundary string to detect part end (null for non-multipart)
     * @return Fully parsed MimePart with decoded content and hash values set
     * @throws IOException If an error occurs reading from the stream or during decoding
     */
    private MimePart parsePartContent(boolean isTextPart, MimeHeaders headers, String boundary) throws IOException {
        boolean isBase64 = false;
        boolean isQuotedPrintable = false;
        boolean isUuencode = false;

        // Get encoding.
        Optional<MimeHeader> cte = headers.get("content-transfer-encoding");
        if (cte.isPresent()) {
            String encoding = cte.get().getValue();

            isBase64 = encoding.compareToIgnoreCase("base64") == 0;
            isQuotedPrintable = encoding.compareToIgnoreCase("quoted-printable") == 0;
            isUuencode = encoding.compareToIgnoreCase("uuencode") == 0
                    || encoding.compareToIgnoreCase("x-uuencode") == 0
                    || encoding.compareToIgnoreCase("x-uue") == 0;
        }

        try {
            // Build digests.
            MessageDigest digestSha1 = MessageDigest.getInstance(HashType.SHA_1.getKey());
            MessageDigest digestSha256 = MessageDigest.getInstance(HashType.SHA_256.getKey());
            MessageDigest digestMD5 = MessageDigest.getInstance(HashType.MD_5.getKey());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MimePart part;
            byte[] bytes;
            while ((bytes = readLine()) != null) {
                String line = new String(bytes);
                if (atBoundary(line) || isBoundaryLine(line, boundary) || isEndBoundaryLine(line, boundary)) {
                    pendingLine = bytes;
                    break;
                }
                baos.write(bytes);
            }

            byte[] content = baos.toByteArray();
            if (isBase64) {
                content = Base64.decodeBase64(content);
            } else if (isQuotedPrintable) {
                try {
                    content = QuotedPrintableDecoder.decode(content);
                } catch (DecoderException e) {
                    log.warn("EmailParser decoder exception: {}", e.getMessage());
                }
            } else if (isUuencode || UuencodeDecoder.looksLikeUuencode(content)) {
                content = decodeUuencodeOrRaw(content);
            }

            if (isTextPart) {
                part = new TextMimePart(content);
            } else {
                File tempFile = File.createTempFile("mimepart-", ".tmp");
                try {
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(content);
                    }
                    FileMimePart filePart = FileMimePart.temporary(tempFile);
                    ownedParts.add(filePart);
                    part = filePart;
                } catch (IOException | RuntimeException e) {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile.toPath());
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
            }

            // Set part details.
            digestSha1.update(content);
            part.setHash(HashType.SHA_1, Base64.encodeBase64String(digestSha1.digest()));

            digestSha256.update(content);
            part.setHash(HashType.SHA_256, Base64.encodeBase64String(digestSha256.digest()));

            digestMD5.update(content);
            part.setHash(HashType.MD_5, Base64.encodeBase64String(digestMD5.digest()));

            part.setSize(content.length);

            return part;

        } catch (NoSuchAlgorithmException nsae) {
            throw new IOException("No such algorithm", nsae);
        }
    }

    /**
     * Decodes uuencoded content, falling back to the raw bytes if decoding doesn't
     * actually produce anything (e.g. a false-positive "begin" line match).
     *
     * @param raw Raw content bytes.
     * @return Decoded bytes, or the original raw bytes if decoding failed.
     */
    private static byte[] decodeUuencodeOrRaw(byte[] raw) {
        return UuencodeDecoder.decode(raw).map(UuencodeDecoder.Result::content).orElse(raw);
    }

    /**
     * Closes the EmailParser and cleans up temporary files created for MIME parts.
     * <p>
     * This method deletes temporary files associated with FileMimePart instances
     * that were created during parsing. The main email file is not deleted.
     * <p>
     * This method is called automatically when using try-with-resources.
     */
    @Override
    public synchronized void close() {
        closed = true;
        try {
            stream.close();
        } catch (IOException e) {
            log.warn("Failed to close MIME input stream: {}", e.getMessage());
        }
        for (FileMimePart part : ownedParts) {
            try {
                part.close();
            } catch (IOException e) {
                log.warn("Failed to release temporary MIME part: {}", e.getMessage());
            }
        }
    }
}
