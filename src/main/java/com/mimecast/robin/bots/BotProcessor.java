package com.mimecast.robin.bots;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.connection.Connection;

/**
 * Bot processor interface for email infrastructure analysis bots.
 * <p>Bots automatically analyze incoming emails and their infrastructure,
 * then generate a reply with diagnostic information.
 * <p>Implementations should:
 * <ul>
 *   <li>Parse the incoming email if needed</li>
 *   <li>Analyze the SMTP session, headers, DNS records, etc.</li>
 *   <li>Generate a response email with findings</li>
 *   <li>Queue the response for delivery</li>
 * </ul>
 */
public interface BotProcessor {

    /**
     * Processes an email for bot analysis and generates a response.
     * <p>This method should not block - it will be called from a thread pool.
     *
     * @param connection  SMTP connection instance containing session data.
     * @param emailParser Parsed email instance, may be null if parsing failed.
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
