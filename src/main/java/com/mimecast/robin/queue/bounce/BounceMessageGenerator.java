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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Bounce message generator.
 */
public class BounceMessageGenerator {
    private static final Logger log = LogManager.getLogger(BounceMessageGenerator.class);

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
            MimeMultipart report = new MimeMultipart("report");
            MimeBodyPart explanation = new MimeBodyPart();
            explanation.setText(text, StandardCharsets.UTF_8.name());
            report.addBodyPart(explanation);
            InternetHeaders statusHeaders = new InternetHeaders();
            statusHeaders.setHeader("Content-Type", "message/delivery-status");
            report.addBodyPart(new MimeBodyPart(statusHeaders, status.getBytes(StandardCharsets.UTF_8)));
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
}
