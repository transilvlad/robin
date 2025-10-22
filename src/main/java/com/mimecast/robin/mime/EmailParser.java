package com.mimecast.robin.mime;

import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.headers.MimeHeaders;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.MultipartMimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.smtp.io.LineInputStream;
import com.mimecast.robin.util.QuotedPrintableDecoder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * Example usage:
 * <pre>
 * EmailParser parser = new EmailParser("/path/to/email.eml");
 * parser.parse();
 * MimeHeaders headers = parser.getHeaders();
 * List&lt;MimePart&gt; parts = parser.getParts();
 * </pre>
 *
 * @see MimeHeaders
 * @see MimePart
 * @see LineInputStream
 */
public class EmailParser {
    private static final Logger log = LogManager.getLogger(EmailParser.class);

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
     * Constructs a new EmailParser instance from an existing LineInputStream.
     * <p>
     * Use this constructor for advanced scenarios such as parsing email content from
     * network streams, memory buffers, or other custom input sources. The parser does
     * not take ownership of the stream and will close it after parsing completes.
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
     * @return Unmodifiable list of MimePart objects
     * @see MimePart
     */
    public List<MimePart> getParts() {
        return parts;
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
     * Parsing is a single-pass operation. After calling this method, the parser's
     * state is finalized and cannot be reset.
     *
     * @param headersOnly If true, only headers are parsed; if false, headers and body are parsed
     * @return Self for method chaining
     * @throws IOException If an error occurs while reading the email file
     */
    public EmailParser parse(boolean headersOnly) throws IOException {
        parseHeaders();

        if (!headersOnly) {
            parseBody();
        }

        stream.close();

        return this;
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
        byte[] bytes;
        StringBuilder header = new StringBuilder();
        while ((bytes = stream.readLine()) != null) {

            String line = new String(bytes);

            // If line doesn't start with a whitespace
            // we need to produce a header from what we got so far
            // if any.
            if (!Character.isWhitespace(bytes[0]) && header.length() > 0) {
                headers.put(new MimeHeader(header.toString()));
                header = new StringBuilder();
            }

            // Break if found end of headers.
            if (StringUtils.isBlank(line.trim())) {
                break;
            }

            header.append(line);
        }

        // Last header
        if (header.length() > 0) {
            headers.put(new MimeHeader(header.toString()));
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
     * @throws IOException If an error occurs while reading from the stream
     */
    private void parseBody() throws IOException {
        Optional<MimeHeader> optional = headers.get("Content-Type");
        if (optional.isPresent()) {
            MimeHeader contentType = optional.get();
            String boundary = contentType.getParameter("boundary");

            MimePart part;
            if (contentType.getValue().startsWith("multipart/")) {
                part = new MultipartMimePart();

            } else if (contentType.getValue().startsWith("text/")) {
                part = parsePartContent(true, headers, boundary);

            } else {
                part = parsePartContent(false, headers, boundary);
            }

            // Move over part headers.
            headers.startsWith("content-").forEach(part::addHeader);
            part.getHeaders().get().forEach(headers::remove);

            // Add part.
            parts.add(part);

            if (boundary == null || boundary.isEmpty()) {
                parsePartContent(true, headers, "");
            } else {
                parsePart(boundary);
            }
        }
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
    private String getFileName(MimeHeaders headers) {
        Optional<MimeHeader> optional = headers.get("Content-Disposition");
        if (optional.isPresent() && optional.get().getParameter("filename") != null) {
            return optional.get().getParameter("filename");
        }

        optional = headers.get("Content-Type");
        if (optional.isPresent()) {
            MimeHeader header = optional.get();

            String name = header.getParameter("name");
            if (name != null) {
                return optional.get().getParameter("name");
            }

            String type = header.getCleanValue();
            if (type.equalsIgnoreCase("text/html")) {
                return "part." + parts.size() + ".html";

            } else if (type.equalsIgnoreCase("text/plain")) {
                return "part." + parts.size() + ".txt";

            } else if (type.equalsIgnoreCase("text/calendar")) {
                return "part." + parts.size() + ".cal";

            } else if (type.toLowerCase().startsWith("image/")) {
                return "part." + parts.size() + ".img";

            } else if (type.toLowerCase().startsWith("message/")) {
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
     * @throws IOException If an error occurs while reading from the stream
     */
    private void parsePart(String boundary) throws IOException {
        MimeHeaders partHeaders = new MimeHeaders();
        byte[] bytes;
        StringBuilder header = new StringBuilder();
        while ((bytes = stream.readLine()) != null) {

            String line = new String(bytes);

            // Break on end boundaries.
            if (line.contains(boundary + "--")) {
                break;
            }

            // Skip boundaries.
            if (line.contains(boundary)) {
                continue;
            }

            // If line doens't start with a whitespace
            // we need to produce a header from what we got so far
            // if any.
            if ((!Character.isWhitespace(bytes[0]) || line.trim().isEmpty()) && header.length() > 0) {
                if (!header.toString().trim().isEmpty()) {
                    partHeaders.put(new MimeHeader(header.toString().trim()));
                }
                header = new StringBuilder();
            }

            // Break if found end of headers.
            if (partHeaders.size() > 0 && StringUtils.isBlank(line.trim())) {

                // If line doens't start with a whitespace
                // we need to produce a header from what we got so far
                // if any.
                if (!header.toString().trim().isEmpty()) {
                    partHeaders.put(new MimeHeader(header.toString().trim()));
                }

                header = new StringBuilder();

                // Find out which part it is.
                MimePart part;
                Optional<MimeHeader> optional = partHeaders.get("Content-Type");
                if (optional.isPresent()) {
                    MimeHeader ct = optional.get();

                    if (ct.getValue().startsWith("multipart/")) {
                        part = new MultipartMimePart();
                        partHeaders.get().forEach(h -> part.addHeader(h.getName(), h.getValue()));
                        parts.add(part);
                        parsePart(ct.getParameter("boundary"));

                    } else if (ct.getValue().startsWith("message/rfc822")) {
                        part = parsePartContent(true, partHeaders, boundary);

                        EmailParser rfc822 = new EmailParser(new LineInputStream(new ByteArrayInputStream(part.getBytes()), 1024))
                                .parse();

                        parts.addAll(rfc822.getParts());

                    } else {
                        part = parsePartContent(ct.getValue().startsWith("text/") || ct.getValue().startsWith("message/"), partHeaders, boundary);

                        partHeaders.get().forEach(h -> part.addHeader(h.getName(), h.getValue()));
                        parts.add(part);
                    }

                    partHeaders = new MimeHeaders();
                }
            }

            if (!line.trim().isEmpty()) {
                header.append(line);
            }
        }

        // Last header.
        if (header.length() > 0) {
            headers.put(new MimeHeader(header.toString().trim()));
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

        // Get encoding.
        Optional<MimeHeader> cte = headers.get("content-transfer-encoding");
        if (cte.isPresent()) {
            String encoding = cte.get().getValue();

            isBase64 = encoding.compareToIgnoreCase("base64") == 0;
            isQuotedPrintable = encoding.compareToIgnoreCase("quoted-printable") == 0;
        }

        try {
            // Build digests.
            MessageDigest digestSha1 = MessageDigest.getInstance(HashType.SHA_1.getKey());
            MessageDigest digestSha256 = MessageDigest.getInstance(HashType.SHA_256.getKey());
            MessageDigest digestMD5 = MessageDigest.getInstance(HashType.MD_5.getKey());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteArrayOutputStream content = new ByteArrayOutputStream();

            byte[] bytes;
            while ((bytes = stream.readLine()) != null) {
                String line = new String(bytes);
                if (boundary != null && !boundary.isEmpty() && line.contains(boundary)) {
                    if (line.contains(boundary + "--")) {
                        stream.unread(bytes);
                    }
                    break;
                }

                baos.write(bytes);
            }

            // Decode if needed.
            if (isBase64) {
                content.write(Base64.decodeBase64(baos.toByteArray()));

            } else if (isQuotedPrintable) {
                try {
                    content.write(QuotedPrintableDecoder.decode(baos.toByteArray()));

                } catch (DecoderException de) {
                    log.error("EmailParser decoder exception: {}", de.getMessage());
                    content.write(baos.toByteArray());
                }
            } else {
                content.write(baos.toByteArray());
            }
            baos.close();

            // Construct part.
            MimePart part;
            if (isTextPart) {
                part = new TextMimePart(content.toByteArray());

            } else {
                part = new FileMimePart();
            }

            // Set part details.
            digestSha1.update(content.toByteArray());
            part.setHash(HashType.SHA_1, Base64.encodeBase64String(digestSha1.digest()));

            digestSha256.update(content.toByteArray());
            part.setHash(HashType.SHA_256, Base64.encodeBase64String(digestSha256.digest()));

            digestMD5.update(content.toByteArray());
            part.setHash(HashType.MD_5, Base64.encodeBase64String(digestMD5.digest()));

            part.setSize(content.size());

            return part;

        } catch (NoSuchAlgorithmException nsae) {
            throw new IOException("No such algorithm", nsae);
        }
    }
}
