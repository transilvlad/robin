package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.DovecotSaslAuthNative;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.verb.MailVerb;
import com.mimecast.robin.smtp.verb.Verb;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * RCPT extension processor.
 */
public class ServerRcpt extends ServerMail {

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

        // Check if users are enabled in configuration and try and authenticate if so.
        if (Config.getServer().isDovecotAuth()) {
            try (DovecotSaslAuthNative dovecotSaslAuthNative = new DovecotSaslAuthNative()) {
                if (!dovecotSaslAuthNative.validate(new MailVerb(verb).getAddress().getAddress(), "smtp")) {
                    connection.write("550 5.1.1 Unknown destination mailbox address [" + connection.getSession().getUID() + "]");
                    return false;
                }
            } catch (Exception e) {
                log.error("Dovecot authentication error: {}", e.getMessage());
                connection.write("451 4.3.2 Internal server error [" + connection.getSession().getUID() + "]");
                return false;
            }
        } else if (Config.getServer().isUsersEnabled()) {
            // Scenario response.
            Optional<ScenarioConfig> opt = connection.getScenario();
            if (opt.isPresent() && opt.get().getRcpt() != null) {
                for (Map<String, String> entry : opt.get().getRcpt()) {
                    if (getAddress() != null && getAddress().getAddress().matches(entry.get("value"))) {
                        String response = entry.get("response");
                        if (response.startsWith("2") && !connection.getSession().getEnvelopes().isEmpty()) {
                            connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());
                        }
                        connection.write(response);
                        return response.startsWith("2");
                    }
                }
            }
        }

        // Accept all.
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            connection.getSession().getEnvelopes().getLast().addRcpt(getAddress().getAddress());
        }
        connection.write("250 2.1.5 Recipient OK [" + connection.getSession().getUID() + "]");

        return true;
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
