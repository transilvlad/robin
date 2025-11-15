package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.DovecotUserLookupNative;
import com.mimecast.robin.smtp.ProxyEmailDelivery;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.SmtpException;
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

        // Check for proxy rule match first (only first matching rule proxies).
        Optional<Map<String, Object>> proxyRule = Optional.empty();
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            String mailFrom = connection.getSession().getEnvelopes().getLast().getMail();
            proxyRule = ProxyMatcher.findMatchingRule(
                connection.getSession().getFriendAddr(),
                connection.getSession().getEhlo(),
                mailFrom,
                getAddress().getAddress(),
                Config.getServer().getProxy());
        }

        // If proxy rule matches, handle proxy connection.
        if (proxyRule.isPresent()) {
            return handleProxyRecipient(connection, proxyRule.get());
        }

        // Check if this specific recipient should be blackholed.
        boolean blackholedRecipient = false;
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            String mailFrom = connection.getSession().getEnvelopes().getLast().getMail();
            blackholedRecipient = BlackholeMatcher.shouldBlackhole(
                connection.getSession().getFriendAddr(),
                connection.getSession().getEhlo(),
                mailFrom,
                getAddress().getAddress(),
                Config.getServer().getBlackholeConfig());
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
            } else if (Config.getServer().isUsersEnabled()) {
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
     * Handles a recipient that matches a proxy rule.
     *
     * @param connection Connection instance.
     * @param rule       Proxy rule that matched.
     * @return Boolean indicating success.
     * @throws IOException Unable to communicate.
     */
    private boolean handleProxyRecipient(Connection connection, Map<String, Object> rule) throws IOException {
        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelope available for proxy recipient");
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }

        Object proxyConnectionObj = connection.getSession().getEnvelopes().getLast().getProxyConnection();
        
        // If this is the first recipient match, establish proxy connection.
        if (proxyConnectionObj == null) {
            try {
                ProxyEmailDelivery proxyDelivery = establishProxyConnection(connection, rule);
                connection.getSession().getEnvelopes().getLast().setProxyConnection(proxyDelivery);
                log.info("Established proxy connection for first matching recipient");
            } catch (SmtpException e) {
                log.error("SMTP error establishing proxy connection: {}", e.getMessage());
                String errorResponse = String.format("451 4.4.1 Proxy connection failed [%s]", connection.getSession().getUID());
                connection.getSession().getEnvelopes().getLast().setProxyConnection(errorResponse);
                connection.write(errorResponse);
                return false;
            } catch (IOException e) {
                log.error("Failed to establish proxy connection: {}", e.getMessage());
                // If connection fails before first recipient, reject all subsequent recipients with this error.
                String errorResponse = String.format("451 4.4.1 Proxy connection failed [%s]", connection.getSession().getUID());
                connection.getSession().getEnvelopes().getLast().setProxyConnection(errorResponse);
                connection.write(errorResponse);
                return false;
            }
            
            // Re-get the connection object after setting it
            proxyConnectionObj = connection.getSession().getEnvelopes().getLast().getProxyConnection();
        }
        
        // Check if it's an error string from previous failed connection
        if (proxyConnectionObj instanceof String) {
            // Connection failed earlier, reject with stored error.
            connection.write((String) proxyConnectionObj);
            return false;
        }
        
        // Must be a ProxyEmailDelivery at this point
        if (!(proxyConnectionObj instanceof ProxyEmailDelivery)) {
            log.error("Unexpected proxy connection type: {}", proxyConnectionObj.getClass().getName());
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }
        
        ProxyEmailDelivery proxyDelivery = (ProxyEmailDelivery) proxyConnectionObj;

        // Send RCPT TO to proxy server.
        try {
            String proxyResponse = proxyDelivery.sendRcpt(getAddress().getAddress());
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
     * @throws IOException   Unable to communicate.
     * @throws SmtpException SMTP error.
     */
    private ProxyEmailDelivery establishProxyConnection(Connection connection, Map<String, Object> rule) 
            throws IOException, SmtpException {
        // Create proxy session.
        Session proxySession = new Session();
        proxySession.setDirection(EmailDirection.OUTBOUND);
        proxySession.setMx(java.util.Collections.singletonList(ProxyMatcher.getHost(rule)));
        proxySession.setPort(ProxyMatcher.getPort(rule));
        
        // Set protocol-specific parameters.
        String protocol = ProxyMatcher.getProtocol(rule);
        if ("lmtp".equalsIgnoreCase(protocol)) {
            proxySession.setLhlo(Config.getServer().getHostname());
        } else {
            proxySession.setEhlo(Config.getServer().getHostname());
        }
        
        // Set TLS if configured.
        if (ProxyMatcher.isTls(rule)) {
            proxySession.setStartTls(true);
        }

        // Create proxy delivery and connect.
        ProxyEmailDelivery proxyDelivery = new ProxyEmailDelivery(
            proxySession,
            connection.getSession().getEnvelopes().getLast()
        );
        
        try {
            proxyDelivery.connect();
        } catch (SmtpException e) {
            throw new SmtpException(e);
        } catch (IOException e) {
            throw new IOException("Failed to establish proxy connection: " + e.getMessage(), e);
        }
        
        return proxyDelivery;
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
