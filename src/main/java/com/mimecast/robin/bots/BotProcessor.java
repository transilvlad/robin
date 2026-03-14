package com.mimecast.robin.bots;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.connection.Connection;

/**
 * Bot processor interface for email infrastructure analysis bots.
 * <p>Bots automatically analyze incoming emails and their infrastructure,
 * then generate a reply with diagnostic information.
 * <p>Implementations should:
 * <ul>
 *   <li>Use the provided {@link EmailParser} to access email headers and content</li>
 *   <li>Analyze the SMTP session, headers, DNS records, etc.</li>
 *   <li>Generate a response email with findings</li>
 *   <li>Queue the response for delivery</li>
 * </ul>
 *
 * <p>Thread safety: Each bot receives a cloned session and its own {@link EmailParser} instance.
 * File-backed messages use reference counting to ensure files are not deleted until all
 * consumers have finished processing.
 */
public interface BotProcessor {

    /**
     * Processes an email for bot analysis and generates a response.
     * <p>This method is called from a dedicated bot thread pool.
     * <p>Each bot receives its own {@link EmailParser} instance created from the message source,
     * allowing safe concurrent access to the email content.
     *
     * @param connection  SMTP connection instance containing cloned session data.
     * @param emailParser Parsed email instance (headers only). May be null if the message
     *                    source is unavailable or parsing failed.
     * @param botAddress  The bot address that matched (e.g., "robot+token@example.com").
     */
    void process(Connection connection, EmailParser emailParser, String botAddress);

    /**
     * Gets the name of this bot for factory registration.
     *
     * @return Bot name.
     */
    String getName();
}
