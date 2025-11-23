package com.mimecast.robin.bots;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for resolving reply addresses in bot requests.
 * <p>Provides common logic for determining where bot responses should be sent.
 */
public class BotReplyAddressResolver {
    private static final Logger log = LogManager.getLogger(BotReplyAddressResolver.class);

    /**
     * Pattern to extract reply address from sieve format.
     * <p>Format: robot+user+domain.com@botdomain.com or robot+token+user+domain.com@botdomain.com
     * <p>Everything after the first or second + and before the final @ is the reply address,
     * <br>with the first + in that section converted to @.
     * <p>Examples:
     * <ul>
     *   <li>robot+admin+internal.com@robin.local → admin@internal.com</li>
     *   <li>robot+abc+user+example.org@robin.local → user@example.org</li>
     * </ul>
     * Group 1: Everything after first + (optional token + reply address)
     */
    private static final Pattern REPLY_SIEVE_PATTERN = Pattern.compile(
            "^[^+]+(?:\\+[^+]+)?\\+(.+)@[^@]+$"
    );

    /**
     * Determines the reply address based on the sieve address, headers, or envelope.
     * <p>Priority order:
     * <ol>
     *   <li>Sieve reply address (robot+user+domain.com@example.com or robot+token+user+domain.com@example.com)</li>
     *   <li>Reply-To header from envelope (extracted before parser closed)</li>
     *   <li>From header from envelope (extracted before parser closed)</li>
     *   <li>Envelope MAIL FROM</li>
     * </ol>
     *
     * @param connection SMTP connection.
     * @param botAddress The bot address that was matched.
     * @return Reply address or null if none found.
     */
    public static String resolveReplyAddress(Connection connection, String botAddress) {
        // Match both robot+user+domain.com@botdomain.com
        // and robot+token+user+domain.com@botdomain.com
        Matcher matcher = REPLY_SIEVE_PATTERN.matcher(botAddress);
        if (matcher.matches()) {
            String replyPart = matcher.group(1);
            int plusIndex = replyPart.indexOf('+');
            if (plusIndex >= 0) {
                String user = replyPart.substring(0, plusIndex);
                String domain = replyPart.substring(plusIndex + 1);
                String replyAddress = user + "@" + domain;
                log.debug("Using sieve reply address: {}", replyAddress);
                return replyAddress;
            }
        }

        if (!connection.getSession().getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();

            // Try Reply-To header first.
            String replyTo = envelope.getHeaders().get("X-Parsed-Reply-To");
            if (replyTo != null && !replyTo.isEmpty()) {
                String extracted = extractEmailAddress(replyTo);
                if (extracted != null) {
                    log.debug("Using Reply-To header from envelope: {}", extracted);
                    return extracted;
                }
            }

            // Try From header.
            String from = envelope.getHeaders().get("X-Parsed-From");
            if (from != null && !from.isEmpty()) {
                String extracted = extractEmailAddress(from);
                if (extracted != null) {
                    log.debug("Using From header from envelope: {}", extracted);
                    return extracted;
                }
            }

            // Fall back to envelope MAIL FROM.
            String mailFrom = envelope.getMail();
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
    public static String extractEmailAddress(String headerValue) {
        try {
            InternetAddress address = new InternetAddress(headerValue);
            return address.getAddress();
        } catch (AddressException e) {
            // Try simple extraction if parsing fails.
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

    private BotReplyAddressResolver() {
        // Utility class, no instantiation.
    }
}

