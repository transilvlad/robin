/**
 * Email infrastructure analysis bots that automatically respond with diagnostic information.
 *
 * <p>Bots are special recipient addresses that trigger automated analysis and response generation.
 * <br>When an email is received to a bot address, the bot analyzes the SMTP session, email headers,
 * <br>DNS records, and other infrastructure details, then generates a reply with findings.
 *
 * <h2>Features:</h2>
 * <ul>
 *     <li>Pattern-based bot address matching with token validation</li>
 *     <li>Domain and IP restrictions for security</li>
 *     <li>Asynchronous processing using thread pool (non-blocking)</li>
 *     <li>Bot addresses are excluded from normal storage processors</li>
 *     <li>Bot addresses skip Dovecot LDA and local mailbox storage</li>
 *     <li>Blackholed addresses are excluded from bot processing</li>
 * </ul>
 *
 * <h2>Available Bots:</h2>
 * <ul>
 *     <li><b>SessionBot</b> - Analyzes complete SMTP session and returns JSON report</li>
 *     <li><b>EmailAnalysisBot</b> - Performs comprehensive email security and infrastructure analysis</li>
 * </ul>
 *
 * <h2>Bot Address Formats:</h2>
 * <p>The SessionBot supports sieve-style reply address encoding:
 * <ul>
 *     <li>{@code robotSession@example.com} - replies to From or envelope sender</li>
 *     <li>{@code robotSession+token@example.com} - same as above with token</li>
 *     <li>{@code robotSession+token+reply+user@domain.com@example.com} - replies to user@domain.com</li>
 * </ul>
 *
 * <h2>Configuration:</h2>
 * <p>Bots are configured in {@code cfg/bots.json5}:
 * <pre>
 * {
 *   enabled: true,
 *   poolSize: 10,
 *   bots: [
 *     {
 *       botName: "session",
 *       addressPattern: "^robot.*@example\\.com$",
 *       tokenValidation: true,
 *       tokenPattern: "^[a-zA-Z0-9]{8,}$",
 *       allowedDomains: ["example.com"],
 *       allowedIps: ["192.168.1.0/24"]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Implementation:</h2>
 * <p>To create a custom bot, implement the {@link com.mimecast.robin.bots.BotProcessor} interface
 * <br>and register it using {@code Factories.addBot(botName, botProcessor)}.
 *
 * <p>Bots run asynchronously in a dedicated thread pool managed by {@link com.mimecast.robin.main.Server}.
 * <br>The {@link com.mimecast.robin.smtp.extension.server.ServerRcpt} extension identifies bot addresses
 * <br>and records them in the {@link com.mimecast.robin.smtp.MessageEnvelope}.
 * <br>The {@link com.mimecast.robin.storage.LocalStorageClient} invokes bot processors after storage
 * <br>processing is complete.
 *
 * <h2>Thread Safety:</h2>
 * <p>Bot processing is thread-safe:
 * <ul>
 *     <li>Session and envelope are cloned before passing to bot thread pool</li>
 *     <li>EmailParser is not passed to bots (null) to avoid accessing closed resources</li>
 *     <li>Bots use envelope headers extracted before parser closure</li>
 * </ul>
 *
 * @see com.mimecast.robin.bots.BotProcessor
 * @see com.mimecast.robin.bots.SessionBot
 * @see com.mimecast.robin.config.server.BotConfig
 * @see com.mimecast.robin.main.Factories#addBot(String, BotProcessor)
 */
package com.mimecast.robin.bots;
