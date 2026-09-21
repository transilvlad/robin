package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
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
 * <p>Thread safety: Each bot receives a cloned session. Bots matched for the same message run
 * sequentially with one fully parsed {@link EmailParser} instance. Implementations must treat
 * the parser as read-only and must not close it.
 * File-backed messages use reference counting to ensure files are not deleted until processing
 * has finished.
 */
public interface BotProcessor {

    /**
     * Processes an email for bot analysis and generates a response.
     * <p>This method is called from a dedicated bot thread pool.
     * <p>Bots matched for the same message share one fully parsed {@link EmailParser} and are
     * invoked sequentially. Repeated calls to {@link EmailParser#parse()} are harmless no-ops.
     *
     * @param connection    SMTP connection instance containing cloned session data.
     * @param emailParser   Fully parsed, read-only email instance.
     * @param botAddress    The bot address that matched (e.g., "robot+token@example.com").
     * @param botDefinition Bot definition containing configuration like endpoint URL.
     */
    void process(Connection connection, EmailParser emailParser, String botAddress, BotConfig.BotDefinition botDefinition);

    /**
     * Gets the name of this bot for factory registration.
     *
     * @return Bot name.
     */
    String getName();
}
