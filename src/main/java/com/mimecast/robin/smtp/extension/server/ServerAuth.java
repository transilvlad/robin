package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.UserConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Extensions;
import com.mimecast.robin.sasl.DovecotSaslAuthNative;
import com.mimecast.robin.sasl.SqlAuthProvider;
import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.AuthVerb;
import com.mimecast.robin.smtp.verb.Verb;
import org.apache.commons.codec.binary.Base64;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * AUTH extension processor.
 * TODO Implement DIGEST-MD5 support.
 */
public class ServerAuth extends ServerProcessor {

    /**
     * Advert getter.
     *
     * @return Advert string.
     */
    @Override
    public String getAdvert() {
        return Config.getServer().isAuth() ? "AUTH PLAIN LOGIN" : "";
    }

    /**
     * AUTH processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        // Check if port is inbound and secure or submission.
        if (connection.getSession().isInbound() && !connection.getSession().isSecurePort()) {
            connection.write(SmtpResponses.AUTH_NOT_SUPPORTED_538);
            return false;
        }

        // Check if connection is secure.
        if (!connection.getSession().isStartTls()) {
            connection.write(SmtpResponses.CONNECTION_NOT_SECURED_538);
            return false;
        }

        // Process authentication.
        AuthVerb authVerb = new AuthVerb(verb);
        if (verb.getCount() > 1) {
            switch (authVerb.getType()) {
                case "PLAIN":
                    processAuthPlain(verb);
                    break;

                case "LOGIN":
                    processAuthLogin(verb);
                    break;

                default:
                    break;
            }

            // Get available users for authentication.
            if (!connection.getSession().getUsername().isEmpty()) {
                // Check if users are enabled in configuration and try and authenticate if so.
                if (Config.getServer().getDovecot().isAuth()) {
                    String backend = Config.getServer().getDovecot().getAuthBackend();
                    if ("sql".equalsIgnoreCase(backend)) {
                        SqlAuthProvider sqlAuth = SqlAuthManager.getAuthProvider();
                        if (sqlAuth == null) {
                            log.error("SQL auth requested but SqlAuthManager not initialized");
                            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
                            return false;
                        }
                        try {
                            if (sqlAuth.authenticate(connection.getSession().getUsername(), connection.getSession().getPassword())) {
                                connection.getSession().setAuth(true);
                                connection.getSession().setDirection(EmailDirection.OUTBOUND);
                                connection.write(SmtpResponses.AUTH_SUCCESS_235);
                                return true;
                            } else {
                                connection.write(SmtpResponses.AUTH_FAILED_535);
                                return false;
                            }
                        } catch (Exception e) {
                            log.error("SQL authentication error: {}", e.getMessage());
                            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
                            return false;
                        }
                    } else {
                        try (DovecotSaslAuthNative dovecotSaslAuthNative = new DovecotSaslAuthNative(Path.of(Config.getServer().getDovecot().getAuthSocket().getClient()))) {
                            // Attempt to authenticate against Dovecot.
                            if (dovecotSaslAuthNative.authenticate(
                                    authVerb.getType(),
                                    connection.getSession().isStartTls(),
                                    connection.getSession().getUsername(),
                                    connection.getSession().getPassword(),
                                    "smtp",
                                    connection.getSession().getAddr(),
                                    connection.getSession().getFriendAddr()
                            )) {
                                connection.getSession().setAuth(true);
                                connection.getSession().setDirection(EmailDirection.OUTBOUND);
                                connection.write(SmtpResponses.AUTH_SUCCESS_235);
                                return true;
                            } else {
                                connection.write(SmtpResponses.AUTH_FAILED_535);
                                return false;
                            }
                        } catch (Exception e) {
                            log.error("Dovecot authentication error: {}", e.getMessage());
                        }
                    }
                } else if (Config.getServer().getUsers().isListEnabled()) {
                    // Scenario response.
                    Optional<UserConfig> opt = Config.getServer().getUsers().getUser(connection.getSession().getUsername());
                    if (opt.isPresent() && opt.get().getPass().equals(connection.getSession().getPassword())) {
                        connection.getSession().setAuth(true);
                        connection.getSession().setDirection(EmailDirection.OUTBOUND);
                        connection.write(SmtpResponses.AUTH_SUCCESS_235);
                        return true;
                    } else {
                        connection.write(SmtpResponses.AUTH_FAILED_535);
                        return false;
                    }
                } else {
                    connection.write(String.format(SmtpResponses.UNKNOWN_MAILBOX_550, connection.getSession().getUID()));
                    return false;
                }
            } else {
                connection.write(String.format(SmtpResponses.UNKNOWN_MAILBOX_550, connection.getSession().getUID()));
                return false;
            }
        }

        connection.write(SmtpResponses.UNRECOGNIZED_AUTH_504);
        return false;
    }

    /**
     * Process auth plain.
     *
     * @param verb Verb instance.
     * @throws IOException Unable to communicate.
     */
    private void processAuthPlain(Verb verb) throws IOException {
        String auth;

        if (verb.getCount() == 2) {
            connection.write(SmtpResponses.AUTH_PAYLOAD_334); // Payload:

            auth = connection.read();
            if (Extensions.isExtension(auth)) return; // Failsafe to catch unexpected commands.

            String decoded = new String(Base64.decodeBase64(auth));
            String[] parts = decoded.split("\u0000");
            if (parts.length == 3) {
                connection.getSession().setUsername(parts[1]);
                connection.getSession().setPassword(parts[2]);
            }
        }
    }

    /**
     * Process auth login.
     *
     * @param verb Verb instance.
     * @throws IOException Unable to communicate.
     */
    private void processAuthLogin(Verb verb) throws IOException {
        String user;
        String pass;

        if (verb.getCount() > 2) {
            user = new String(Base64.decodeBase64(verb.getPart(2))); if (Extensions.isExtension(user)) return; // Failsafe to catch unexpected commands.
            connection.write(SmtpResponses.AUTH_PASSWORD_334); // Password:

            pass = connection.read();
            pass = new String(Base64.decodeBase64(pass)); if (Extensions.isExtension(pass)) return; // Failsafe to catch unexpected commands.
        } else {
            connection.write(SmtpResponses.AUTH_USERNAME_334); // Username:
            user = connection.read();
            user = new String(Base64.decodeBase64(user)); if (Extensions.isExtension(user)) return; // Failsafe to catch unexpected commands.

            connection.write(SmtpResponses.AUTH_PASSWORD_334); // Password:
            pass = connection.read();
            pass = new String(Base64.decodeBase64(pass)); if (Extensions.isExtension(pass)) return; // Failsafe to catch unexpected commands.
        }

        connection.getSession().setUsername(user);
        connection.getSession().setPassword(pass);
    }
}
