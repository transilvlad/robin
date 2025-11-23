package com.mimecast.robin.bots;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mx.SessionRouting;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Utility class for common bot operations.
 * <p>Provides shared functionality for bot processors including address stripping,
 * email storage, and response queueing.
 */
public class BotHelper {
    private static final Logger log = LogManager.getLogger(BotHelper.class);

    /**
     * Strips token and reply address from bot address.
     * <p>Converts robotSession+token+reply+user@domain.com@example.com to robotSession@example.com
     *
     * @param botAddress Bot address to strip.
     * @return Stripped bot address.
     */
    public static String stripBotAddress(String botAddress) {
        if (botAddress == null || !botAddress.contains("+")) {
            return botAddress;
        }

        // Extract base address and domain.
        int firstPlusIndex = botAddress.indexOf('+');
        int atIndex = botAddress.lastIndexOf('@');

        if (firstPlusIndex != -1 && atIndex != -1 && firstPlusIndex < atIndex) {
            String prefix = botAddress.substring(0, firstPlusIndex);
            String domain = botAddress.substring(atIndex);
            return prefix + domain;
        }

        return botAddress;
    }

    /**
     * Writes the email to an .eml file in the store folder.
     *
     * @param emailStream The email content stream.
     * @param sessionUid  The session UID for filename.
     * @return The absolute path to the written .eml file.
     * @throws IOException If an I/O error occurs.
     */
    public static String writeEmailToStore(ByteArrayOutputStream emailStream, String sessionUid) throws IOException {
        String basePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        Path storePath = Paths.get(basePath);
        Files.createDirectories(storePath);

        // Create unique filename using thread-safe DateTimeFormatter.
        String filename = String.format("%s-%s.eml",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")),
                sessionUid);
        Path emailFile = storePath.resolve(filename);

        // Write email to disk.
        try (FileOutputStream fos = new FileOutputStream(emailFile.toFile())) {
            emailStream.writeTo(fos);
            log.debug("Wrote bot response email to store: {}", emailFile);
        }

        return emailFile.toString();
    }

    /**
     * Queues a bot response email for delivery.
     * <p>This method handles the common logic for creating and queueing bot response emails:
     * <ul>
     *   <li>Creates message envelope</li>
     *   <li>Creates outbound session</li>
     *   <li>Resolves MX records via SessionRouting</li>
     *   <li>Builds MIME email with provided parts</li>
     *   <li>Writes email to store</li>
     *   <li>Queues for delivery</li>
     * </ul>
     *
     * @param originalSession Original SMTP session.
     * @param botAddress      Bot address that received the request.
     * @param replyTo         Recipient address.
     * @param subject         Email subject line.
     * @param mimeParts       List of MIME parts to include in the email.
     * @throws IOException If an I/O error occurs during email creation or storage.
     */
    public static void queueBotResponse(Session originalSession, String botAddress, String replyTo,
                                        String subject, List<MimePart> mimeParts) throws IOException {
        // Create envelope for response.
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail(stripBotAddress(botAddress));
        envelope.setRcpt(replyTo);
        envelope.setSubject(subject);

        // Create outbound session for delivery.
        Session outboundSession = new Session();
        outboundSession.setDirection(EmailDirection.OUTBOUND);
        outboundSession.setEhlo(Config.getServer().getHostname());
        outboundSession.getEnvelopes().add(envelope);

        // Use SessionRouting to resolve MX records for the recipient domain.
        SessionRouting routing = new SessionRouting(outboundSession);
        var routedSessions = routing.getSessions();

        if (routedSessions.isEmpty()) {
            log.error("Dropping response, no MX routes found for recipient: {}", replyTo);
            return;
        }

        // Use the first routed session (primary MX).
        Session routedSession = routedSessions.getFirst();
        if (routedSession.getMx() == null || routedSession.getMx().isEmpty()) {
            log.error("Dropping response, no MX records found for recipient: {}", replyTo);
            return;
        }

        // Get the envelope from the routed session sinc it was cloned.
        envelope = routedSession.getEnvelopes().getFirst();

        // Build MIME email with EmailBuilder.
        ByteArrayOutputStream emailStream = new ByteArrayOutputStream();
        EmailBuilder builder = new EmailBuilder(routedSession, envelope)
                .addHeader("Subject", envelope.getSubject())
                .addHeader("To", replyTo)
                .addHeader("From", envelope.getMail());

        // Add all provided MIME parts.
        for (MimePart part : mimeParts) {
            builder.addPart(part);
        }

        builder.writeTo(emailStream);

        // Write email to .eml file in store folder.
        String emlFilePath = writeEmailToStore(emailStream, routedSession.getUID());

        // Set the file path on the envelope (not the stream).
        envelope.setFile(emlFilePath);

        // Create relay session and queue.
        RelaySession relaySession = new RelaySession(routedSession);
        relaySession.setProtocol("ESMTP");

        // Persist envelope files to queue folder and delete original.
        var originalFile = Path.of(envelope.getFile());
        QueueFiles.persistEnvelopeFiles(relaySession);
        Files.deleteIfExists(originalFile);

        // Queue for delivery.
        PersistentQueue.getInstance().enqueue(relaySession);

        log.info("Queued bot response for delivery to: {}", replyTo);
    }
}
