package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.GsonExclusionStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
 *   <li>robotSession+token+user+domain.com@example.com - replies to user@domain.com</li>
 * </ul>
 * <p>In the sieve format, the reply address is encoded with + instead of @.
 * <br>Example: robotSession+abc+admin+internal.com@robin.local → replies to admin@internal.com
 */
public class SessionBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(SessionBot.class);

    /**
     * Gson instance for serializing session data with exclusion strategy.
     */
    private static final Gson GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new GsonExclusionStrategy())
            .setPrettyPrinting()
            .create();


    /**
     * Processes the session bot request.
     *
     * @param connection  SMTP connection.
     * @param emailParser Parsed email (may be null - bots run async after parser is closed).
     * @param botAddress  The bot address that was matched.
     */
    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress) {
        try {
            log.info("Processing session bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID());

            // Determine reply address.
            String replyTo = BotReplyAddressResolver.resolveReplyAddress(connection, botAddress);
            if (replyTo == null || replyTo.isEmpty()) {
                log.warn("Could not determine reply address for bot request from session UID: {}",
                        connection.getSession().getUID());
                return;
            }

            // Create and queue response email.
            queueResponse(connection.getSession(), botAddress, replyTo);

            log.info("Successfully queued session bot response to: {} from session UID: {}",
                    replyTo, connection.getSession().getUID());

        } catch (Exception e) {
            log.error("Error processing session bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID(), e);
        }
    }

    /**
     * Creates the text summary for the email body.
     * <p>The complete session data will be attached as a JSON file by the queueResponse method.
     *
     * @param session Original SMTP session.
     * @return Text summary of the session.
     */
    private String createTextSummary(Session session) {
        StringBuilder body = new StringBuilder();
        body.append("SMTP Session Analysis Report\n");
        body.append("============================\n\n");
        body.append("Session UID: ").append(session.getUID()).append("\n");
        body.append("Date: ").append(LocalDateTime.now()).append("\n\n");

        body.append("Connection Information:\n");
        body.append("-----------------------\n");
        body.append("Remote IP: ").append(session.getFriendAddr() != null ? session.getFriendAddr() : "N/A").append("\n");
        body.append("Remote rDNS: ").append(session.getFriendRdns() != null ? session.getFriendRdns() : "N/A").append("\n");
        body.append("EHLO/HELO: ").append(session.getEhlo()).append("\n");

        if (session.isTls()) {
            body.append("TLS: Yes\n");
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

        body.append("\n");
        body.append("The complete session data is attached as a JSON file.\n");

        return body.toString();
    }

    /**
     * Queues the response for delivery.
     *
     * @param session    Original SMTP session to analyze.
     * @param botAddress Bot address that received the request.
     * @param replyTo    Recipient address.
     */
    private void queueResponse(Session session, String botAddress, String replyTo) {
        try {
            // Create text summary.
            String textSummary = createTextSummary(session);

            // Generate session JSON with exclusion strategy.
            String sessionJson = GSON.toJson(session);

            // Create MIME parts for the email.
            List<MimePart> parts = new ArrayList<>();
            parts.add(new TextMimePart(textSummary.getBytes(StandardCharsets.UTF_8))
                    .addHeader("Content-Type", "text/plain; charset=\"UTF-8\"")
                    .addHeader("Content-Transfer-Encoding", "7bit")
            );
            parts.add(new TextMimePart(sessionJson.getBytes(StandardCharsets.UTF_8))
                    .addHeader("Content-Type", "application/json; charset=\"UTF-8\"; name=\"session.json\"")
                    .addHeader("Content-Transfer-Encoding", "7bit")
                    .addHeader("Content-Disposition", "attachment; filename=\"session.json\"")
            );

            // Use BotHelper to queue the response.
            BotHelper.queueBotResponse(
                    session,
                    botAddress,
                    replyTo,
                    "Robin Session BOT - " + session.getUID(),
                    parts
            );

            log.info("Queued session bot response for delivery to: {}", replyTo);

        } catch (IOException e) {
            log.error("Failed to queue session bot response: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "session";
    }
}
