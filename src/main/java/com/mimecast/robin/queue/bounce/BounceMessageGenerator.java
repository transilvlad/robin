package com.mimecast.robin.queue.bounce;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Bounce message generator.
 */
public class BounceMessageGenerator {
    private static final Logger log = LogManager.getLogger(BounceMessageGenerator.class);

    /**
     * Most original header bytes returned in the notification.
     */
    static final int MAX_ORIGINAL_HEADER_BYTES = 64 * 1024;

    private final RelaySession relaySession;
    private final String recipient;
    private final MessageEnvelope envelope;

    /**
     * Constructs a new instance of BounceMessageGenerator with given RelaySession instance and recipient.
     *
     * @param relaySession RelaySession instance.
     * @param recipient Recipient email address.
     */
    public BounceMessageGenerator(RelaySession relaySession, String recipient) {
        this(relaySession, relaySession.getSession().getEnvelopes().stream()
                .filter(envelope -> envelope.getRcpts() != null && envelope.getRcpts().contains(recipient))
                .findFirst().orElseGet(() -> relaySession.getSession().getEnvelopes().getLast()), recipient);
    }

    public BounceMessageGenerator(RelaySession relaySession, MessageEnvelope envelope, String recipient) {
        this.relaySession = relaySession;
        this.recipient = recipient;
        this.envelope = java.util.Objects.requireNonNull(envelope);
    }

    /**
     * Generates the complete bounce message as a ByteArrayOutputStream.
     *
     * @return ByteArrayOutputStream containing the bounce message.
     */
    public ByteArrayOutputStream getStream() {
        BounceGenerator bounceGenerator = new BounceGenerator(relaySession, envelope);
        String text = bounceGenerator.generatePlainText(recipient);
        String status = bounceGenerator.generateDeliveryStatus(recipient);

        // Build MIME.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            MimeMessage message = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
            message.setSubject("Delivery Status Notification (Failure)", StandardCharsets.UTF_8.name());
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(originalEnvelope().getMail()));
            message.setFrom(new InternetAddress("mailer-daemon@" + Config.getServer().getHostname()));
            message.setSentDate(new Date());
            message.setHeader("Auto-Submitted", "auto-replied");

            // Tie the notification to the original so clients (and tests) can correlate them.
            byte[] originalHeaders = originalHeaders();
            String originalMessageId = originalMessageId(originalHeaders);
            if (originalMessageId != null) {
                message.setHeader("In-Reply-To", originalMessageId);
                message.setHeader("References", originalMessageId);
            }

            MimeMultipart report = new MimeMultipart("report");
            MimeBodyPart explanation = new MimeBodyPart();
            explanation.setText(text, StandardCharsets.UTF_8.name());
            report.addBodyPart(explanation);
            InternetHeaders statusHeaders = new InternetHeaders();
            statusHeaders.setHeader("Content-Type", "message/delivery-status");
            report.addBodyPart(new MimeBodyPart(statusHeaders, status.getBytes(StandardCharsets.UTF_8)));

            // Third part: the original headers (RFC 6522 section 3), not the body.
            if (originalHeaders != null) {
                InternetHeaders returnedHeaders = new InternetHeaders();
                returnedHeaders.setHeader("Content-Type", "text/rfc822-headers");
                report.addBodyPart(new MimeBodyPart(returnedHeaders, originalHeaders));
            }
            message.setContent(report);
            message.saveChanges();
            message.setHeader("Content-Type", report.getContentType() + "; report-type=delivery-status");
            message.writeTo(stream);
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("Failed to build delivery status notification", e);
        }

        return stream;
    }

    /**
     * Builds a null-reverse-path notification to the original sender. A null
     * sender suppresses the notification instead of generating a bounce loop.
     * An unroutable reverse path is suppressed too, since no retry can deliver it.
     */
    public RelaySession toRelaySession() {
        String sender = originalEnvelope().getMail();
        if (sender == null || sender.isBlank() || "<>".equals(sender)) {
            return null;
        }
        int at = sender.lastIndexOf('@');
        if (at < 1 || at == sender.length() - 1) {
            log.warn("Suppressing delivery status notification to unroutable reverse path: recipient={}", recipient);
            return null;
        }
        Session session = new Session().setMx(List.of(sender.substring(at + 1)))
                .setEhlo(Config.getServer().getHostname()).setTls(true)
                .addEnvelope(new MessageEnvelope().setMail("").setRcpt(sender)
                        .setBytes(getStream().toByteArray()));
        return new RelaySession(session).setProtocol("mx");
    }

    private MessageEnvelope originalEnvelope() {
        return envelope;
    }

    /**
     * Reads the original message's header block, up to {@link #MAX_ORIGINAL_HEADER_BYTES}.
     *
     * @return Header bytes ending in an empty line, or null when the message cannot be read.
     */
    byte[] originalHeaders() {
        try (InputStream input = openOriginal()) {
            if (input == null) {
                return null;
            }
            byte[] head = input.readNBytes(MAX_ORIGINAL_HEADER_BYTES);
            int end = headerEnd(head);
            if (end < 0) {
                // No separator in range: return whole lines only, ended with an empty line.
                int lastLf = lastIndexOf(head, (byte) '\n');
                if (lastLf < 0) {
                    return null;
                }
                byte[] lines = java.util.Arrays.copyOf(head, lastLf + 1);
                ByteArrayOutputStream out = new ByteArrayOutputStream(lines.length + 2);
                out.writeBytes(lines);
                out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
                return out.toByteArray();
            }
            return java.util.Arrays.copyOf(head, end);
        } catch (IOException e) {
            log.warn("Unable to read original headers for delivery status notification: {}", e.getMessage());
            return null;
        }
    }

    private InputStream openOriginal() throws IOException {
        // getStream() opens a fresh stream from the message source or bytes; never the raw
        // construction-time stream, which would be consumed.
        InputStream stream = envelope.getStream();
        if (stream != null) {
            return stream;
        }
        String file = envelope.getFile();
        return file != null && !file.isBlank() && Files.isReadable(Path.of(file))
                ? Files.newInputStream(Path.of(file)) : null;
    }

    private static String originalMessageId(byte[] headers) {
        if (headers == null) {
            return null;
        }
        try {
            String value = new InternetHeaders(new ByteArrayInputStream(headers)).getHeader("Message-ID", null);
            if (value == null) {
                return null;
            }
            value = value.replaceAll("\\s+", "");
            return value.matches("<[^<>@]+@[^<>@]+>") ? value : null;
        } catch (MessagingException e) {
            return null;
        }
    }

    /**
     * Finds the end of the header block, including the empty line.
     */
    private static int headerEnd(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') continue;
            if (i + 1 < bytes.length && bytes[i + 1] == '\n') return i + 2;
            if (i + 2 < bytes.length && bytes[i + 1] == '\r' && bytes[i + 2] == '\n') return i + 3;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] bytes, byte value) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == value) return i;
        }
        return -1;
    }
}
