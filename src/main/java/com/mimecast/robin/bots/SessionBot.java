package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.mx.SessionRouting;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session bot that replies with SMTP session analysis.
 * <p>This bot analyzes the complete SMTP session including:
 * <ul>
 *   <li>Connection information (IP, TLS, authentication)</li>
 *   <li>SMTP transaction details</li>
 *   <li>Email headers and envelope</li>
 *   <li>DNS and infrastructure information</li>
 * </ul>
 * <p>The response is sent as a JSON attachment with a text summary.
 *
 * <p>Address format supports reply-to sieve parsing:
 * <ul>
 *   <li>robotSession@example.com - replies to From or envelope sender</li>
 *   <li>robotSession+token@example.com - same as above with token</li>
 *   <li>robotSession+token+reply+user@domain.com@example.com - replies to user@domain.com</li>
 * </ul>
 */
public class SessionBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(SessionBot.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Pattern to extract reply address from sieve format.
     * Matches: robotSession+token+reply+user@domain.com@example.com
     * Group 1: token
     * Group 2: user@domain.com (reply address)
     */
    private static final Pattern REPLY_SIEVE_PATTERN = Pattern.compile(
            "^[^+]+(?:\\+([^+]+))?\\+reply\\+([^@]+@[^@]+)@.+$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress) {
        try {
            log.info("Processing session bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID());

            // Determine reply address
            String replyTo = determineReplyAddress(connection, emailParser, botAddress);
            if (replyTo == null || replyTo.isEmpty()) {
                log.warn("Could not determine reply address for bot request from session UID: {}",
                        connection.getSession().getUID());
                return;
            }

            // Generate session JSON
            String sessionJson = GSON.toJson(connection.getSession());

            // Create response email
            MessageEnvelope responseEnvelope = createResponseEmail(
                    connection.getSession(),
                    botAddress,
                    replyTo,
                    sessionJson
            );

            // Queue for delivery
            queueResponse(responseEnvelope, replyTo);

            log.info("Successfully queued session bot response to: {} from session UID: {}",
                    replyTo, connection.getSession().getUID());

        } catch (Exception e) {
            log.error("Error processing session bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID(), e);
        }
    }

    /**
     * Determines the reply address based on the sieve address, headers, or envelope.
     * <p>Priority order:
     * <ol>
     *   <li>Sieve reply address (robotSession+token+reply+user@domain.com@example.com)</li>
     *   <li>Reply-To header from parsed email</li>
     *   <li>From header from parsed email</li>
     *   <li>Envelope MAIL FROM</li>
     * </ol>
     *
     * @param connection  SMTP connection.
     * @param emailParser Parsed email (may be null).
     * @param botAddress  The bot address that was matched.
     * @return Reply address or null if none found.
     */
    private String determineReplyAddress(Connection connection, EmailParser emailParser, String botAddress) {
        // Check for sieve reply address
        Matcher matcher = REPLY_SIEVE_PATTERN.matcher(botAddress);
        if (matcher.matches()) {
            String replyAddress = matcher.group(2);
            if (replyAddress != null && !replyAddress.isEmpty()) {
                log.debug("Using sieve reply address: {}", replyAddress);
                return replyAddress;
            }
        }

        // Check parsed email headers
        if (emailParser != null) {
            // Try Reply-To header first
            Optional<com.mimecast.robin.mime.headers.MimeHeader> replyToHeader = 
                    emailParser.getHeaders().get("Reply-To");
            if (replyToHeader.isPresent()) {
                String cleanedReplyTo = extractEmailAddress(replyToHeader.get().getValue());
                if (cleanedReplyTo != null) {
                    log.debug("Using Reply-To header: {}", cleanedReplyTo);
                    return cleanedReplyTo;
                }
            }

            // Try From header
            Optional<com.mimecast.robin.mime.headers.MimeHeader> fromHeader = 
                    emailParser.getHeaders().get("From");
            if (fromHeader.isPresent()) {
                String cleanedFrom = extractEmailAddress(fromHeader.get().getValue());
                if (cleanedFrom != null) {
                    log.debug("Using From header: {}", cleanedFrom);
                    return cleanedFrom;
                }
            }
        }

        // Fall back to envelope MAIL FROM
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            String mailFrom = connection.getSession().getEnvelopes().getLast().getMail();
            if (mailFrom != null && !mailFrom.isEmpty()) {
                log.debug("Using envelope MAIL FROM: {}", mailFrom);
                return mailFrom;
            }
        }

        return null;
    }

    /**
     * Extracts email address from a header value.
     * <p>Handles formats like "Name &lt;email@example.com&gt;" or "email@example.com".
     *
     * @param headerValue Header value.
     * @return Extracted email address or null.
     */
    private String extractEmailAddress(String headerValue) {
        try {
            InternetAddress address = new InternetAddress(headerValue);
            return address.getAddress();
        } catch (AddressException e) {
            // Try simple extraction if parsing fails
            if (headerValue.contains("<") && headerValue.contains(">")) {
                int start = headerValue.indexOf('<') + 1;
                int end = headerValue.indexOf('>');
                if (start > 0 && end > start) {
                    return headerValue.substring(start, end);
                }
            }
            return headerValue.trim();
        }
    }

    /**
     * Strips token and reply address from bot address.
     * <p>Converts robotSession+token+reply+user@domain.com@example.com to robotSession@example.com
     *
     * @param botAddress Bot address to strip.
     * @return Stripped bot address.
     */
    private String stripBotAddress(String botAddress) {
        if (botAddress == null || !botAddress.contains("+")) {
            return botAddress;
        }
        
        // Extract base address and domain
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
     * Creates the response email with session JSON.
     *
     * @param session     Original SMTP session.
     * @param botAddress  Bot address that received the request.
     * @param replyTo     Address to send reply to.
     * @param sessionJson Session data as JSON.
     * @return Response envelope ready to queue.
     */
    private MessageEnvelope createResponseEmail(Session session, String botAddress,
                                                 String replyTo, String sessionJson) {
        MessageEnvelope envelope = new MessageEnvelope();

        // Set envelope addresses - strip token and reply address from bot address
        envelope.setMail(stripBotAddress(botAddress));
        envelope.setRcpt(replyTo);

        // Set subject
        envelope.setSubject("Robin Session BOT - " + session.getUID());

        // Create email body with both text summary and JSON
        StringBuilder body = new StringBuilder();
        body.append("SMTP Session Analysis Report\n");
        body.append("============================\n\n");
        body.append("Session UID: ").append(session.getUID()).append("\n");
        body.append("Date: ").append(envelope.getDate()).append("\n\n");

        body.append("Connection Information:\n");
        body.append("-----------------------\n");
        body.append("Remote IP: ").append(session.getFriendAddr() != null ? session.getFriendAddr() : "N/A").append("\n");
        body.append("Remote rDNS: ").append(session.getFriendRdns() != null ? session.getFriendRdns() : "N/A").append("\n");
        body.append("EHLO/HELO: ").append(session.getEhlo()).append("\n");

        if (session.isAuth() && session.getUsername() != null && !session.getUsername().isEmpty()) {
            body.append("Authenticated: Yes\n");
            body.append("Username: ").append(session.getUsername()).append("\n");
        } else {
            body.append("Authenticated: No\n");
        }

        if (session.isTls()) {
            body.append("TLS: Yes\n");
            // Note: Protocol and cipher are arrays, not single values
            if (session.getProtocols() != null && session.getProtocols().length > 0) {
                body.append("TLS Protocols: ").append(String.join(", ", session.getProtocols())).append("\n");
            }
            if (session.getCiphers() != null && session.getCiphers().length > 0) {
                body.append("TLS Ciphers: ").append(String.join(", ", session.getCiphers())).append("\n");
            }
        } else {
            body.append("TLS: No\n");
        }

        body.append("\n");
        body.append("Envelope Information:\n");
        body.append("---------------------\n");
        if (!session.getEnvelopes().isEmpty()) {
            MessageEnvelope originalEnvelope = session.getEnvelopes().getLast();
            body.append("MAIL FROM: ").append(originalEnvelope.getMail()).append("\n");
            body.append("RCPT TO: ").append(String.join(", ", originalEnvelope.getRcpts())).append("\n");
        }

        body.append("\n\n");
        body.append("Complete Session Data (JSON):\n");
        body.append("============================\n");
        body.append(sessionJson);

        // Set the body
        envelope.setMessage(body.toString());

        return envelope;
    }

    /**
     * Queues the response for delivery.
     *
     * @param envelope Response envelope.
     * @param replyTo  Recipient address.
     */
    private void queueResponse(MessageEnvelope envelope, String replyTo) {
        try {
            // Create outbound session for delivery
            Session outboundSession = new Session();
            outboundSession.setDirection(EmailDirection.OUTBOUND);
            outboundSession.setEhlo(Config.getServer().getHostname());

            // Add envelope to session
            outboundSession.getEnvelopes().add(envelope);

            // Use SessionRouting to resolve MX records for the recipient domain
            SessionRouting routing = new SessionRouting(outboundSession);
            var routedSessions = routing.getSessions();
            
            if (routedSessions.isEmpty()) {
                log.warn("No MX routes found for recipient: {}", replyTo);
                return;
            }

            // Use the first routed session (primary MX)
            Session routedSession = routedSessions.get(0);

            // Build email using EmailBuilder with text body and JSON attachment
            String sessionJson = envelope.getMessage();
            
            // Extract text summary from message (everything before the JSON section)
            String textSummary = sessionJson;
            String jsonSectionHeader = "Complete Session Data (JSON):";
            String separator = "============================";
            int jsonStartIndex = sessionJson.indexOf(jsonSectionHeader);
            if (jsonStartIndex != -1) {
                textSummary = sessionJson.substring(0, jsonStartIndex).trim();
                // Extract just the JSON part
                int jsonDataIndex = sessionJson.indexOf(separator, jsonStartIndex);
                if (jsonDataIndex != -1) {
                    sessionJson = sessionJson.substring(jsonDataIndex + separator.length()).trim();
                }
            }

            // Build MIME email with EmailBuilder
            ByteArrayOutputStream emailStream = new ByteArrayOutputStream();
            new EmailBuilder(routedSession, envelope)
                    .addHeader("Subject", envelope.getSubject())
                    .addHeader("To", replyTo)
                    .addHeader("From", envelope.getMail())
                    .addPart(new TextMimePart(textSummary.getBytes(StandardCharsets.UTF_8))
                            .addHeader("Content-Type", "text/plain; charset=\"UTF-8\"")
                            .addHeader("Content-Transfer-Encoding", "7bit")
                    )
                    .addPart(new TextMimePart(sessionJson.getBytes(StandardCharsets.UTF_8))
                            .addHeader("Content-Type", "application/json; charset=\"UTF-8\"; name=\"session.json\"")
                            .addHeader("Content-Transfer-Encoding", "7bit")
                            .addHeader("Content-Disposition", "attachment; filename=\"session.json\"")
                    )
                    .writeTo(emailStream);

            // Persist email to disk before queueing
            String basePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
            Path storePath = Paths.get(basePath, "bots");
            Files.createDirectories(storePath);

            // Create unique filename
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS");
            String filename = String.format("%s-%s.eml", sdf.format(new Date()), routedSession.getUID());
            Path emailFile = storePath.resolve(filename);

            // Write email to disk
            try (FileOutputStream fos = new FileOutputStream(emailFile.toFile())) {
                emailStream.writeTo(fos);
                log.debug("Persisted bot response email to: {}", emailFile);
            }

            // Set the email stream on the envelope for relay
            envelope.setStream(new ByteArrayInputStream(emailStream.toByteArray()));

            // Create relay session and queue
            RelaySession relaySession = new RelaySession(routedSession);
            relaySession.setProtocol("ESMTP");

            // Queue for delivery
            PersistentQueue.getInstance().enqueue(relaySession);

            log.info("Queued session bot response for delivery to: {} (persisted: {})", replyTo, emailFile);
            
        } catch (IOException e) {
            log.error("Failed to queue session bot response: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "session";
    }
}
