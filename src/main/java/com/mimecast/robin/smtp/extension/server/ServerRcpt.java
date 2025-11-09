package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.DovecotUserLookupNative;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.security.BlackholeMatcher;
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

        // Check if this specific recipient should be blackholed
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
                            // Only add recipient if not blackholed
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

        // Accept all, but only add recipient if not blackholed
        if (!connection.getSession().getEnvelopes().isEmpty() && !blackholedRecipient) {
            connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());
        }
        
        // If recipient was blackholed, mark the envelope as blackholed if it has no recipients
        if (blackholedRecipient && !connection.getSession().getEnvelopes().isEmpty()) {
            if (connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty()) {
                connection.getSession().getEnvelopes().getLast().setBlackholed(true);
            }
        }
        
        connection.write(String.format(SmtpResponses.RECIPIENT_OK_250, connection.getSession().getUID()));

        return true;
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
