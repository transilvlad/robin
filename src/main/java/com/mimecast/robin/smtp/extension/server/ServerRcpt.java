package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ProxyRule;
import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.DovecotUserLookupNative;
import com.mimecast.robin.smtp.ProxyEmailDelivery;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.security.BlackholeMatcher;
import com.mimecast.robin.smtp.security.ProxyMatcher;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.verb.MailVerb;
import com.mimecast.robin.smtp.verb.Verb;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * RCPT extension processor.
 */
public class ServerRcpt extends ServerMail {

    /**
     * Recipients limit.
     */
    private int recipientsLimit = 100;

    /**
     * RCPT processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        // Check recipients limit before adding new recipient.
        if (connection.getSession().getEnvelopes().getLast().getRcpts().size() >= recipientsLimit) {
            connection.write(String.format(SmtpResponses.RECIPIENTS_LIMIT_EXCEEDED_452, connection.getSession().getUID()));
            return false;
        }

        // Get MAIL FROM for matching logic (extracted to avoid duplication).
        String mailFrom = getMailFrom(connection);

        // Check for blackhole first to avoid processing bot addresses that will be blackholed.
        boolean blackholedRecipient = BlackholeMatcher.shouldBlackhole(
                connection.getSession().getFriendAddr(),
                connection.getSession().getEhlo(),
                mailFrom,
                getAddress().getAddress(),
                Config.getServer().getBlackholeConfig());

        // Check for bot address match and record it (skip if blackholed).
        if (!blackholedRecipient) {
            checkBotAddress(connection);
        }

        // Check for proxy rule match first (only first matching rule proxies).
        Optional<ProxyRule> proxyRule = ProxyMatcher.findMatchingRule(
                connection.getSession().getFriendAddr(),
                connection.getSession().getEhlo(),
                mailFrom,
                getAddress().getAddress(),
                connection.getSession().isInbound(),
                Config.getServer().getProxy());

        // If proxy rule matches, handle proxy connection.
        if (proxyRule.isPresent()) {
            return handleProxyRecipient(connection, proxyRule.get());
        }

        // When receiving inbound email.
        if (connection.getSession().isInbound()) {
            // Check if users are enabled in configuration and try and authenticate if so.
            if (Config.getServer().getDovecot().getBooleanProperty("auth")) {
                try (DovecotUserLookupNative dovecotUserLookupNative = new DovecotUserLookupNative(Path.of(Config.getServer().getDovecot().getStringProperty("authUserdbSocket")))) {
                    if (!dovecotUserLookupNative.validate(new MailVerb(verb).getAddress().getAddress(), "smtp")) {
                        connection.write(String.format(SmtpResponses.UNKNOWN_MAILBOX_550, connection.getSession().getUID()));
                        return false;
                    }
                } catch (Exception e) {
                    log.error("Dovecot user lookup error: {}", e.getMessage());
                    connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
                    return false;
                }
            } else if (Config.getServer().getUsers().isListEnabled()) {
                // Scenario response.
                Optional<ScenarioConfig> opt = connection.getScenario();
                if (opt.isPresent() && opt.get().getRcpt() != null) {
                    for (Map<String, String> entry : opt.get().getRcpt()) {
                        if (getAddress() != null && getAddress().getAddress().matches(entry.get("value"))) {
                            String response = entry.get("response");
                            // Only add recipient if not blackholed.
                            if (response.startsWith("2") && !connection.getSession().getEnvelopes().isEmpty() && !blackholedRecipient) {
                                connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());
                            }
                            connection.write(response);
                            return response.startsWith("2");
                        }
                    }
                }
            }
        }

        // Accept all, but only add recipient if not blackholed.
        if (!connection.getSession().getEnvelopes().isEmpty() && !blackholedRecipient) {
            connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());
        }

        // If recipient was blackholed, mark the envelope as blackholed if it has no recipients.
        if (blackholedRecipient && !connection.getSession().getEnvelopes().isEmpty()) {
            if (connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty()) {
                connection.getSession().getEnvelopes().getLast().setBlackholed(true);
            }
        }

        connection.write(String.format(SmtpResponses.RECIPIENT_OK_250, connection.getSession().getUID()));

        return true;
    }

    /**
     * Gets MAIL FROM address from the current envelope.
     * <p>Helper method to avoid code duplication in proxy and blackhole checks.
     *
     * @param connection Connection instance.
     * @return MAIL FROM address or null if no envelope exists.
     */
    private String getMailFrom(Connection connection) {
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            return connection.getSession().getEnvelopes().getLast().getMail();
        }
        return null;
    }

    /**
     * Handles a recipient that matches a proxy rule.
     *
     * @param connection Connection instance.
     * @param rule       Proxy rule that matched.
     * @return Boolean indicating success.
     * @throws IOException Unable to communicate.
     */
    private boolean handleProxyRecipient(Connection connection, ProxyRule rule) throws IOException {
        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelope available for proxy recipient");
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }

        // Get or create proxy connection from session (for connection reuse).
        ProxyEmailDelivery proxyConnection = connection.getSession().getProxyConnection(rule);

        // If no connection exists for this rule, establish new connection.
        if (proxyConnection == null) {
            proxyConnection = establishProxyConnection(connection, rule);
            connection.getSession().setProxyConnection(rule, proxyConnection);
        } else {
            log.debug("Reusing existing proxy connection for rule - Host: {}", rule.getHost());
        }

        // Check if it's an error string from previous failed connection.
        if (proxyConnection == null || !proxyConnection.isConnected()) {
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }

        // Prepare connection for this envelope if it's a new envelope.
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            proxyConnection.prepareForEnvelope(connection.getSession().getEnvelopes().getLast());
        }

        // Send RCPT TO to proxy server.
        try {
            String proxyResponse = proxyConnection.sendRcpt(getAddress().getAddress());
            log.debug("Proxy RCPT response: {}", proxyResponse);

            // Add recipient to local envelope for tracking.
            connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());

            // Forward the proxy server's response to client.
            connection.write(proxyResponse + " [" + connection.getSession().getUID() + "]");
            return proxyResponse.startsWith("250");

        } catch (IOException e) {
            log.error("Failed to send RCPT to proxy: {}", e.getMessage());
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }
    }

    /**
     * Establishes a new proxy connection.
     *
     * @param connection Connection instance.
     * @param rule       Proxy rule.
     * @return ProxyEmailDelivery instance.
     */
    private ProxyEmailDelivery establishProxyConnection(Connection connection, ProxyRule rule) {
        // Create proxy session.
        Session proxySession = new Session();
        proxySession.setDirection(EmailDirection.OUTBOUND);
        proxySession.setMx(java.util.Collections.singletonList(rule.getHost()));
        proxySession.setPort(rule.getPort());

        // Set protocol-specific parameters.
        String protocol = rule.getProtocol();
        if ("lmtp".equalsIgnoreCase(protocol)) {
            proxySession.setLhlo(Config.getServer().getHostname());
        } else {
            proxySession.setEhlo(Config.getServer().getHostname());
        }

        // Set TLS if configured.
        if (rule.isTls()) {
            proxySession.setStartTls(true);
        }

        // Set authentication if configured.
        if (rule.hasAuth()) {
            proxySession.setAuth(true);
            proxySession.setUsername(rule.getAuthUsername());
            proxySession.setPassword(rule.getAuthPassword());
        }

        // Create proxy delivery and connect.
        try {
            return new ProxyEmailDelivery(proxySession, connection.getSession().getEnvelopes().getLast())
                    .connect();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets recipients limit.
     *
     * @param limit Limit value.
     * @return ServerMail instance.
     */
    public ServerRcpt setRecipientsLimit(int limit) {
        this.recipientsLimit = limit;
        return this;
    }

    /**
     * Checks if the recipient address matches any bot patterns and records matches.
     * <p>Bot addresses are matched against configured patterns with domain and IP restrictions.
     * <p>If a match is found, the bot address and bot name are recorded in the envelope.
     *
     * @param connection Connection instance.
     * @throws IOException RCPT address parsing problem.
     */
    private void checkBotAddress(Connection connection) throws IOException {
        if (connection.getSession().getEnvelopes().isEmpty()) {
            return;
        }

        String recipientAddress = getAddress().getAddress();
        if (recipientAddress == null || recipientAddress.isEmpty()) {
            return;
        }

        // Get bot configuration.
        var botConfig = Config.getServer().getBots();
        var botDefinitions = botConfig.getBots();

        if (botDefinitions.isEmpty()) {
            return; // No bots configured.
        }

        // Get remote IP.
        String remoteIp = connection.getSession().getFriendAddr();

        // Check each bot definition.
        for (var botDef : botDefinitions) {
            // Check if address matches pattern.
            if (!botDef.matchesAddress(recipientAddress)) {
                continue;
            }

            // Check authorization (IP or token).
            if (!botDef.isAuthorized(recipientAddress, remoteIp)) {
                log.debug("Bot address {} matched pattern but not authorized (IP: {})",
                        recipientAddress, remoteIp);
                continue;
            }

            // All checks passed - record the bot address.
            String botName = botDef.getBotName();
            connection.getSession().getEnvelopes().getLast()
                    .addBotAddress(recipientAddress, botName);

            log.info("Bot address matched: {} -> bot: {} from IP: {}",
                    recipientAddress, botName, remoteIp);
        }
    }

    /**
     * Gets RCPT TO address.
     *
     * @return Address instance.
     * @throws IOException RCPT address parsing problem.
     */
    @Override
    public InternetAddress getAddress() throws IOException {
        if (address == null) {
            try {
                address = new InternetAddress(verb.getParam("to"));
            } catch (AddressException e) {
                throw new IOException(e);
            }
        }

        return address;
    }
}
