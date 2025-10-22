package com.mimecast.robin.mime;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.Magic;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EmailBuilder is a fluent MIME email message generator that constructs RFC 2822 compliant
 * email messages with support for single-part and multipart structures.
 * <p>
 * This builder handles:
 * <ul>
 *     <li>Header generation and encoding (UTF-8, RFC 2047 encoded words)</li>
 *     <li>Automatic insertion of required headers (Date, Message-ID, From, To, Subject)</li>
 *     <li>Multipart message composition (mixed, related, alternative)</li>
 *     <li>Content encoding (Base64, Quoted-Printable, plain text)</li>
 *     <li>Attachment and inline content management</li>
 *     <li>Header parameter extraction and manipulation</li>
 * </ul>
 * <p>
 * The builder groups MIME parts by type:
 * <ul>
 *     <li><b>Mixed</b> - Attachments and unrelated content</li>
 *     <li><b>Related</b> - Inline content referenced by other parts (images, stylesheets)</li>
 *     <li><b>Alternative</b> - Multiple representations of the same content (text/plain, text/html)</li>
 * </ul>
 * <p>
 * The resulting multipart hierarchy follows RFC 2046 standards:
 * {@code multipart/mixed > multipart/related > multipart/alternative}
 * <p>
 * This builder is designed as a reusable library for any Java application needing to
 * construct email messages programmatically. It can be integrated into MTA implementations,
 * email composition tools, or notification systems.
 * <p>
 * Example usage:
 * <pre>
 * Session session = new Session();
 * MessageEnvelope envelope = new MessageEnvelope();
 * envelope.setMail("sender@example.com");
 * envelope.addRcpt("recipient@example.com");
 *
 * EmailBuilder builder = new EmailBuilder(session, envelope);
 * builder.addHeader("Subject", "Test Email")
 *        .addHeader("From", "sender@example.com")
 *        .buildMime();
 *
 * ByteArrayOutputStream output = new ByteArrayOutputStream();
 * builder.writeTo(output);
 * </pre>
 *
 * @see MimeHeader
 * @see MimePart
 * @see MessageEnvelope
 * @see Session
 */
public class EmailBuilder {
    static final Logger log = LogManager.getLogger(EmailBuilder.class);

    /**
     * Session instance containing configuration and variable substitution context.
     * Used for magic token replacement in headers and message content.
     */
    protected final Session session;

    /**
     * MessageEnvelope instance containing sender, recipients, and MIME configuration.
     * Provides the high-level structure and content for the email message.
     */
    protected final MessageEnvelope envelope;

    /**
     * List of parsed and generated email headers.
     * Includes standard headers (From, To, Date, etc.) and custom headers.
     * Headers are encoded using RFC 2047 for non-ASCII characters.
     */
    protected final List<MimeHeader> headers = new ArrayList<>();

    /**
     * MIME parts grouped by type for multipart message composition.
     * <b>mixed</b> - Contains attachments and unrelated content (lowest priority in hierarchy)
     * <b>related</b> - Contains inline content referenced by other parts (images, stylesheets)
     * <b>alternative</b> - Contains alternative representations of same content (text/plain, text/html)
     */
    protected final List<MimePart> mixed = new ArrayList<>();
    protected final List<MimePart> related = new ArrayList<>();
    protected final List<MimePart> alternative = new ArrayList<>();

    /**
     * Debug flag to enable logging of text part body content.
     * When enabled, the content of all text/* parts is logged after parsing.
     * Useful for debugging MIME composition but should be disabled in production.
     */
    protected boolean logTextPartsBody = false;

    /**
     * Constructs a new EmailBuilder instance with given Session and MessageEnvelope.
     * <p>
     * Initializes the builder with MIME version 1.0 header. The session provides context
     * for variable substitution in headers and content, while the envelope supplies sender,
     * recipients, and base MIME configuration for the message.
     *
     * @param session  Session instance containing configuration and variable context
     * @param envelope MessageEnvelope instance containing sender, recipients, and MIME configuration
     */
    public EmailBuilder(Session session, MessageEnvelope envelope) {
        this.session = session;
        this.envelope = envelope;
        headers.add(new MimeHeader("MIME-Version", "1.0"));
    }

    /**
     * Builds the email structure from the MessageEnvelope MIME configuration.
     * <p>
     * Extracts all MIME parts from the envelope's MimeConfig and categorizes them:
     * <ul>
     *     <li>Parts with Content-ID header are classified as <b>related</b></li>
     *     <li>Text parts without attachment disposition are classified as <b>alternative</b></li>
     *     <li>All other parts are classified as <b>mixed</b></li>
     * </ul>
     * Also processes all headers from the envelope configuration with magic token replacement.
     * Optional logging of text part bodies is available for debugging.
     *
     * @return Self for method chaining
     */
    @SuppressWarnings("unchecked")
    public EmailBuilder buildMime() {
        if (envelope.getMime() != null && !envelope.getMime().isEmpty()) {
            envelope.getMime().getHeaders().forEach(h -> addHeader(h.getName(), Magic.magicReplace(h.getValue(), session)));

            for (MimePart part : envelope.getMime().getParts(session, envelope)) {
                MimeHeader ct = part.getHeader("Content-Type");
                MimeHeader cd = part.getHeader("Content-Disposition");

                // Related parts.
                if (part.getHeader("Content-ID") != null) {
                    related.add(part);

                }

                // Text parts.
                else if (ct != null && ct.getCleanValue().startsWith("text/") && (cd == null || !cd.getCleanValue().startsWith("attachment"))) {

                    // If logging enabled.
                    if (logTextPartsBody) {

                        try {
                            log.info("Text Part Body: {}", new String(part.getBytes())
                                    .replaceAll("\r\n|\r|\n", "\\\\")
                            );
                        } catch (IOException e) {
                            log.error("Text Part Body read error: {}", e.getMessage());
                        }
                    }

                    alternative.add(part);

                }

                // Mixed parts.
                else {
                    mixed.add(part);
                }
            }
        }

        return this;
    }

    /**
     * Adds an email header with automatic encoding for non-ASCII characters.
     * <p>
     * Applies RFC 2047 encoded-word encoding for headers containing non-ASCII characters
     * or multiline values. Multiline values are encoded in Base64 format, while single-line
     * values use appropriate MIME encoding. If encoding fails, the header is added with
     * unfolded newlines for maximum compatibility.
     *
     * @param name  Header name (e.g., "Subject", "From", "X-Custom-Header")
     * @param value Header value, may contain non-ASCII characters or newlines
     * @return Self for method chaining
     */
    public EmailBuilder addHeader(String name, String value) {
        try {
            boolean multiline = value.contains("\r") || value.contains("\n");
            headers.add(new MimeHeader(name,
                    multiline ?
                            "=?UTF-8?B?" + Base64.encodeBase64String(value.getBytes()).trim() + "?=" :
                            MimeUtility.encodeText(value)
            ));
        } catch (UnsupportedEncodingException e) {
            log.warn("Unable to encode header value: {}", e.getMessage());
            headers.add(new MimeHeader(name, value.replaceAll("\n", "\n\t")));
        }
        return this;
    }

    /**
     * Adds missing required RFC 2822 headers before message output.
     * <p>
     * Automatically generates the following headers if not already present:
     * <ul>
     *     <li><b>Date</b> - Current date/time in RFC 2822 format</li>
     *     <li><b>Message-ID</b> - Unique message identifier using UUID</li>
     *     <li><b>Subject</b> - Default subject with Message-ID if not provided</li>
     *     <li><b>From</b> - Sender address from MessageEnvelope</li>
     *     <li><b>To</b> - Recipient addresses from MessageEnvelope</li>
     * </ul>
     * This ensures the generated message is RFC 2822 compliant and can be accepted
     * by SMTP servers and email clients.
     */
    private void addMissingHeaders() {
        List<String> addedHeaders = headers.stream()
                .map(h -> h.getName().toLowerCase())
                .collect(Collectors.toList());

        // Date
        if (!addedHeaders.contains("date")) {
            DateFormat dateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Config.getProperties().getLocale());
            headers.add(new MimeHeader("Date", dateFormat.format(new Date())));
        }

        // Message-ID
        String messageId = "<" + UUID.randomUUID() + ">";
        if (!addedHeaders.contains("message-id")) {
            headers.add(new MimeHeader("Message-ID", messageId));
        }

        // Subject
        if (!addedHeaders.contains("subject")) {
            headers.add(new MimeHeader("Subject", "Robin " + messageId));
        }

        // From
        if (!addedHeaders.contains("from")) {
            headers.add(new MimeHeader("From", "<" + (envelope.getMail() != null ? envelope.getMail() : "") + ">"));
        }

        // To
        if (!addedHeaders.contains("to")) {
            headers.add(new MimeHeader("To", "<" + String.join(">, <", envelope.getRcpts()) + ">"));
        }
    }

    /**
     * Adds a MIME part to the message with automatic categorization.
     * <p>
     * Classifies the part based on headers:
     * <ul>
     *     <li>Parts with Content-ID header → <b>related</b> (inline content)</li>
     *     <li>Text parts without attachment disposition → <b>alternative</b> (content alternatives)</li>
     *     <li>All other parts → <b>mixed</b> (attachments and unrelated content)</li>
     * </ul>
     *
     * @param part MimePart instance to add to the message
     * @return Self for method chaining
     */
    public EmailBuilder addPart(MimePart part) {
        MimeHeader contentType = part.getHeader("content-type");
        if (contentType != null && contentType.getValue().contains("text/") && !contentType.getValue().contains("name=")) {
            alternative.add(part);

        } else if (part.getHeader("content-id") != null) {
            related.add(part);

        } else {
            mixed.add(part);
        }

        return this;
    }

    /**
     * Writes the complete email message to an output stream.
     * <p>
     * Performs the following steps:
     * <ol>
     *     <li>Adds any missing required headers (Date, Message-ID, From, To, Subject)</li>
     *     <li>Writes all headers to the output stream</li>
     *     <li>For single-part messages: writes the part content directly</li>
     *     <li>For multipart messages: constructs nested multipart structure with boundaries</li>
     * </ol>
     * The multipart hierarchy follows this structure (outermost to innermost):
     * {@code multipart/mixed → multipart/related → multipart/alternative}
     *
     * @param outputStream OutputStream to write the complete RFC 2822 message to
     * @return Self for method chaining
     * @throws IOException If an error occurs while writing to the output stream
     */
    public EmailBuilder writeTo(OutputStream outputStream) throws IOException {
        addMissingHeaders();

        // Write haaders
        for (MimeHeader header : headers) {
            outputStream.write(header.toString().getBytes());
        }

        // Single part.
        if (mixed.size() + related.size() + alternative.size() == 1) {
            // Merge parts into one list for eacy extraction of part.
            List<MimePart> parts = new ArrayList<>(mixed);
            parts.addAll(related);
            parts.addAll(alternative);

            parts.get(0).writeTo(outputStream);
        }

        // Multipart.
        else {
            writeMultiparts(outputStream);
        }

        return this;
    }

    /**
     * Writes multipart message structure with appropriate boundaries and nesting.
     * <p>
     * Constructs the multipart hierarchy based on which part categories are present:
     * <ul>
     *     <li>If mixed parts exist: creates outermost multipart/mixed boundary</li>
     *     <li>If related parts exist: creates multipart/related boundary</li>
     *     <li>If alternative parts exist: creates multipart/alternative boundary</li>
     * </ul>
     * Boundaries follow the format: {@code --robinMixed}, {@code --robinRelated}, {@code --robinAlternative}
     * with closing boundaries {@code --robinMixed--}, etc.
     *
     * @param outputStream OutputStream to write the multipart structure to
     * @throws IOException If an error occurs while writing to the output stream
     */
    @SuppressWarnings("java:S1192")
    private void writeMultiparts(OutputStream outputStream) throws IOException {
        if (!mixed.isEmpty()) {
            makeMultipart(outputStream, "mixed");
        }

        if (!related.isEmpty()) {
            makeMultipart(outputStream, "related");
        }

        if (!alternative.isEmpty()) {
            makeMultipart(outputStream, "alternative");
            writeMultipartParts(outputStream, alternative, "alternative");
        }

        if (!related.isEmpty()) {
            if (!alternative.isEmpty()) {
                outputStream.write(("--robin" + StringUtils.capitalize("related") + "\r\n").getBytes());
            }
            writeMultipartParts(outputStream, related, "related");
        }

        if (!mixed.isEmpty()) {
            if (!alternative.isEmpty() || !related.isEmpty()) {
                outputStream.write(("--robin" + StringUtils.capitalize("mixed") + "\r\n").getBytes());
            }
            writeMultipartParts(outputStream, mixed, "mixed");
        }
    }

    /**
     * Writes multipart header section for a given multipart type.
     * <p>
     * Generates a Content-Type header with the appropriate boundary parameter
     * and writes the opening boundary marker. This prepares the output stream for
     * writing the parts that follow within this multipart section.
     *
     * @param outputStream OutputStream to write the multipart header to
     * @param type Multipart type: "mixed", "related", or "alternative"
     * @throws IOException If an error occurs while writing to the output stream
     */
    private void makeMultipart(OutputStream outputStream, String type) throws IOException {
        String boundary = "robin" + StringUtils.capitalize(type);

        outputStream.write(("Content-Type: multipart/" + type + "; boundary=\"" + boundary + "\"\r\n").getBytes());
        outputStream.write("\r\n".getBytes());
        outputStream.write(("--" + boundary + "\r\n").getBytes());
    }

    /**
     * Writes all parts within a multipart section with proper boundary delimiters.
     * <p>
     * Writes each part's content followed by the boundary delimiter. The final part
     * is followed by a closing boundary (with "--" suffix) to indicate the end of
     * the multipart section. Each part is responsible for writing its own headers
     * and content via the MimePart.writeTo() method.
     *
     * @param outputStream OutputStream to write the parts to
     * @param parts List of MimePart instances to write
     * @param type Multipart type used to generate boundary markers
     * @throws IOException If an error occurs while writing to the output stream
     */
    private void writeMultipartParts(OutputStream outputStream, List<MimePart> parts, String type) throws IOException {
        String boundary = "robin" + StringUtils.capitalize(type);

        for (int i = 0; i < parts.size(); i++) {
            parts.get(i).writeTo(outputStream);
            outputStream.write(("--" + boundary + (i == parts.size() - 1 ? "--" : "") + "\r\n").getBytes());
        }
    }

    /**
     * Sets the debug flag to enable/disable logging of text part body content.
     * <p>
     * When enabled (true), the content of all text/* parts discovered during
     * buildMime() will be logged at INFO level with newlines escaped as "\\".
     * This is useful for debugging MIME composition but should be disabled
     * in production environments to avoid logging sensitive message content.
     *
     * @param logTextPartsBody true to enable body logging, false to disable
     * @return Self for method chaining
     */
    public EmailBuilder setLogTextPartsBody(boolean logTextPartsBody) {
        this.logTextPartsBody = logTextPartsBody;
        return this;
    }
}
