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
     * <p>Everything after the first + and before the final @ is captured.
     * <p>Examples:
     * <ul>
     *   <li>robot+admin+internal.com@robin.local → captures "admin+internal.com"</li>
     *   <li>robot+abc+user+example.org@robin.local → captures "abc+user+example.org"</li>
     * </ul>
     * Group 1: Everything after the first + in the local part
     */
    private static final Pattern REPLY_SIEVE_PATTERN = Pattern.compile(
            "^[^+]+\\+(.+)@[^@]+$"
    );

    /**
     * Pattern to detect a per-message reply-source override.
     * <p>Format: robot+envelope@botdomain.com, robot+header@botdomain.com,
     * robot+token+envelope@botdomain.com or robot+token+header@botdomain.com.
     * <p>The keyword must be the final segment (token, if present, always comes first),
     * which keeps this from ever matching an explicit sieve reply address such as
     * robot+header+example.com@botdomain.com (that still resolves to header@example.com).
     * <p>Group 1: Optional token. Group 2: envelope or header (case-insensitive).
     */
    private static final Pattern REPLY_SOURCE_OVERRIDE_PATTERN = Pattern.compile(
            "(?i)^[^+]+\\+(?:([^+@]+)\\+)?(envelope|header)@[^@]+$"
    );

    /**
     * Determines the reply address based on the sieve address, headers, or envelope.
     * <p>Equivalent to {@link #resolveReplyAddress(Connection, String, boolean)} with the
     * bot's default source preference set to header-first, preserving prior behavior.
     *
     * @param connection SMTP connection.
     * @param botAddress The bot address that was matched.
     * @return Reply address or null if none found.
     */
    public static String resolveReplyAddress(Connection connection, String botAddress) {
        return resolveReplyAddress(connection, botAddress, false);
    }

    /**
     * Determines the reply address based on the sieve address, headers, or envelope.
     * <p>Priority order:
     * <ol>
     *   <li>Sieve reply address (robot+user+domain.com@example.com or robot+token+user+domain.com@example.com)</li>
     *   <li>Sieve reply-source override (robot+envelope@example.com or robot+header@example.com,
     *       optionally with a token first), which overrides {@code defaultPreferEnvelope} for this message</li>
     *   <li>Reply-To header, then From header, then envelope MAIL FROM &mdash; or the reverse,
     *       depending on the effective source preference</li>
     * </ol>
     *
     * @param connection            SMTP connection.
     * @param botAddress            The bot address that was matched.
     * @param defaultPreferEnvelope Bot's configured default: true to prefer envelope MAIL FROM
     *                              over header addresses when no per-message override is present.
     * @return Reply address or null if none found.
     */
    public static String resolveReplyAddress(Connection connection, String botAddress, boolean defaultPreferEnvelope) {
        // Handle null or empty bot address.
        if (botAddress == null || botAddress.isEmpty()) {
            return resolveFromEnvelope(connection, defaultPreferEnvelope);
        }

        // Match robot+envelope@botdomain.com, robot+header@botdomain.com,
        // and their robot+token+envelope@... / robot+token+header@... equivalents.
        Matcher overrideMatcher = REPLY_SOURCE_OVERRIDE_PATTERN.matcher(botAddress);
        if (overrideMatcher.matches()) {
            boolean preferEnvelope = "envelope".equalsIgnoreCase(overrideMatcher.group(2));
            log.debug("Using per-message reply source override: {}", preferEnvelope ? "envelope" : "header");
            return resolveFromEnvelope(connection, preferEnvelope);
        }

        // Match both robot+user+domain.com@botdomain.com
        // and robot+token+user+domain.com@botdomain.com
        Matcher matcher = REPLY_SIEVE_PATTERN.matcher(botAddress);
        if (matcher.matches()) {
            String replyPart = matcher.group(1);

            // Count + signs to determine format.
            long plusCount = replyPart.chars().filter(ch -> ch == '+').count();

            if (plusCount == 1) {
                // Format: user+domain (no token).
                int plusIndex = replyPart.indexOf('+');
                String user = replyPart.substring(0, plusIndex);
                String domain = replyPart.substring(plusIndex + 1);

                // Validate that domain contains a dot.
                if (domain.contains(".")) {
                    String replyAddress = user + "@" + domain;
                    log.debug("Using sieve reply address: {}", replyAddress);
                    return replyAddress;
                }
            } else if (plusCount >= 2) {
                // Format: token+user+domain (has token, skip it).
                int firstPlusIndex = replyPart.indexOf('+');
                String remainingPart = replyPart.substring(firstPlusIndex + 1);

                int secondPlusIndex = remainingPart.indexOf('+');
                if (secondPlusIndex >= 0) {
                    String user = remainingPart.substring(0, secondPlusIndex);
                    String domain = remainingPart.substring(secondPlusIndex + 1);

                    // Validate that domain contains a dot.
                    if (domain.contains(".")) {
                        String replyAddress = user + "@" + domain;
                        log.debug("Using sieve reply address: {}", replyAddress);
                        return replyAddress;
                    }
                }
            }
        }

        return resolveFromEnvelope(connection, defaultPreferEnvelope);
    }

    /**
     * Resolves reply address from envelope headers and MAIL FROM.
     *
     * @param connection     SMTP connection.
     * @param preferEnvelope true to try the envelope MAIL FROM before header addresses.
     * @return Reply address or null if none found.
     */
    private static String resolveFromEnvelope(Connection connection, boolean preferEnvelope) {
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();

            // Envelope preferred: try MAIL FROM first, falling back to headers below if blank.
            if (preferEnvelope) {
                String preferredMailFrom = envelope.getMail();
                if (preferredMailFrom != null && !preferredMailFrom.isEmpty()) {
                    log.debug("Using envelope MAIL FROM (preferred source): {}", preferredMailFrom);
                    return preferredMailFrom;
                }
                log.debug("Envelope MAIL FROM unavailable, falling back to header addresses");
            }

            boolean hasReplyToHeader = envelope.getHeaders().containsKey("X-Parsed-Reply-To");
            boolean hasFromHeader = envelope.getHeaders().containsKey("X-Parsed-From");

            // Try Reply-To header first.
            String replyTo = envelope.getHeaders().get("X-Parsed-Reply-To");
            if (replyTo != null && !replyTo.isEmpty() && !replyTo.isBlank()) {
                String extracted = extractEmailAddress(replyTo);
                if (extracted != null && !extracted.isEmpty()) {
                    log.debug("Using Reply-To header from envelope: {}", extracted);
                    return extracted;
                }
            }

            // Try From header.
            String from = envelope.getHeaders().get("X-Parsed-From");
            if (from != null && !from.isEmpty() && !from.isBlank()) {
                String extracted = extractEmailAddress(from);
                if (extracted != null && !extracted.isEmpty()) {
                    log.debug("Using From header from envelope: {}", extracted);
                    return extracted;
                }
            }

            // If headers exist, check if all are blank. If so, return null.
            if (hasReplyToHeader || hasFromHeader) {
                boolean anyHeaderHasValue = false;

                if (hasReplyToHeader && replyTo != null && !replyTo.isEmpty() && !replyTo.isBlank()) {
                    anyHeaderHasValue = true;
                }
                if (hasFromHeader && from != null && !from.isEmpty() && !from.isBlank()) {
                    anyHeaderHasValue = true;
                }

                // If no header has a value (all are blank), return null.
                if (!anyHeaderHasValue) {
                    return null;
                }
            }

            // Fall back to envelope MAIL FROM (already tried above when preferEnvelope is true).
            if (!preferEnvelope) {
                String mailFrom = envelope.getMail();
                if (mailFrom != null && !mailFrom.isEmpty()) {
                    log.debug("Using envelope MAIL FROM: {}", mailFrom);
                    return mailFrom;
                }
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

