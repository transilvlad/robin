package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.security.BlackholeMatcher;
import com.mimecast.robin.smtp.verb.Verb;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.ThreadContext;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAIL extension processor.
 *
 * @see <a href="https://tools.ietf.org/html/rfc1870">RFC 1870</a>
 * @see <a href="https://tools.ietf.org/html/rfc3030">RFC 3030</a>
 * @see <a href="https://tools.ietf.org/html/rfc3461">RFC 3461</a>
 * @see <a href="https://tools.ietf.org/html/rfc6152">RFC 6152</a>
 */
public class ServerMail extends ServerProcessor {
    private static final Pattern MAIL_FROM = Pattern.compile(
            "^MAIL\\s+FROM:\\s*(\\S+)(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE);

    /**
     * MAIL FROM address.
     */
    protected InternetAddress address;

    /**
     * MAIL FROM SIZE param (if any).
     */
    private int size = 0;

    /**
     * MAIL FROM BODY param (if any).
     */
    private String body = "";

    /**
     * MAIL FROM RET param (if any).
     */
    private String ret = "";

    /**
     * MAIL FROM ENVID param (if any).
     */

    private String envId = "";
    /**
     * MAIL FROM NOTIFY list param (if any).
     */
    private String[] notify = new String[]{};

    /**
     * MAIL FROM ORCPT param (if any).
     */
    private InternetAddress oRcpt;

    /**
     * Envelope limit.
     */
    private int envelopeLimit = 100;

    /**
     * Skip MAIL FROM validation flag.
     */
    private boolean skipFromValidation = false;

    /**
     * Advert getter.
     *
     * @return Advert string.
     */
    @Override
    public String getAdvert() {
        return "SMTPUTF8";
    }

    /**
     * MAIL processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        // Bypass for RCPT extension which extends this one.
        if (verb.getKey().equals("mail")) {
            if (!connection.getSession().isAuth() && connection.getSession().isOutbound()) {
                connection.write(String.format(SmtpResponses.AUTH_REQUIRED_530, connection.getSession().getUID()));
                return false;
            }

            // Check envelope limit before adding new envelope.
            if (connection.getSession().getEnvelopes().size() >= envelopeLimit) {
                connection.write(String.format(SmtpResponses.ENVELOPE_LIMIT_EXCEEDED_452, connection.getSession().getUID()));
                return false;
            }

            InternetAddress internetAddress;
            try {
                internetAddress = parseAddress();
            } catch (AddressException e) {
                connection.write(String.format(SmtpResponses.INVALID_ADDRESS_501 + " [%s]", connection.getSession().getUID()));
                return false;
            }
            String addressStr = internetAddress.getAddress();

            // Make envelope.
            MessageEnvelope envelope = new MessageEnvelope();
            connection.getSession().addEnvelope(envelope);

            // Check if envelope should be blackholed based on IP, EHLO, and MAIL FROM.
            if (connection.getSession().isBlackholed() ||
                BlackholeMatcher.shouldBlackhole(
                    connection.getSession().getFriendAddr(),
                    connection.getSession().getEhlo(),
                    addressStr,
                    null,
                    Config.getServer().getBlackholeConfig())) {
                envelope.setBlackholed(true);
            }

            // ScenarioConfig response.
            Optional<ScenarioConfig> opt = connection.getScenario();
            if (opt.isPresent() && opt.get().getMail() != null) {
                if (opt.get().getMail().startsWith("2")) {
                    envelope.setMail(addressStr);
                }
                connection.write(opt.get().getMail());
            }

            // Accept all (with validation).
            else {
                try {
                    // Only an explicit null reverse-path is represented by an empty address.
                    if (!addressStr.isEmpty() && !skipFromValidation) {
                        internetAddress.validate();
                    }
                    envelope.setMail(addressStr);
                    connection.write(String.format(SmtpResponses.SENDER_OK_250, connection.getSession().getUID()));
                } catch (AddressException e) {
                    connection.write(String.format(SmtpResponses.INVALID_ADDRESS_501 + " [%s]", connection.getSession().getUID()));
                    return false;
                }
            }
            ThreadContext.put("cCode", envelope.getMail());

            return true;
        }

        return false;
    }

    /**
     * Sets envelope limit.
     *
     * @param limit Limit value.
     * @return ServerMail instance.
     */
    public ServerMail setEnvelopeLimit(int limit) {
        this.envelopeLimit = limit;
        return this;
    }

    /**
     * Sets skip MAIL FROM validation flag.
     *
     * @param skipFromValidation Boolean.
     * @return ServerMail instance.
     */
    public ServerMail setSkipFromValidation(boolean skipFromValidation) {
        this.skipFromValidation = skipFromValidation;
        return this;
    }

    /**
     * Gets MAIL FROM address.
     *
     * @return Address instance.
     * @throws IOException MAIL address parsing problem.
     */
    public InternetAddress getAddress() throws IOException {
        try {
            return parseAddress();
        } catch (AddressException e) {
            throw new IOException(e);
        }
    }

    private InternetAddress parseAddress() throws AddressException {
        if (address == null) {
            Matcher matcher = MAIL_FROM.matcher(verb.getCommand());
            if (!matcher.matches()) {
                throw new AddressException("Expected MAIL FROM with a reverse-path");
            }
            String from = matcher.group(1);
            if (from.equals("<>")) {
                address = new InternetAddress();
                address.setAddress("");
            } else {
                address = new InternetAddress(from);
            }
        }
        return address;
    }

    /**
     * Gets MAIL FROM SIZE param.
     *
     * @return Size in bytes.
     * @see <a href="https://tools.ietf.org/html/rfc1870">RFC 1870</a>
     */
    public int getSize() {
        if (size == 0) {
            size = Integer.parseInt(verb.getParam("size"));
        }

        return size;
    }

    /**
     * Gets MAIL FROM BODY param.
     *
     * @return BODY string.
     * @see <a href="https://tools.ietf.org/html/rfc3030">RFC 3030</a>
     * @see <a href="https://tools.ietf.org/html/rfc6152">RFC 6152</a>
     */
    public String getBody() {
        if (body.isEmpty()) {
            body = verb.getParam("body");
        }

        return body;
    }

    /**
     * Gets MAIL FROM NOTIFY list param.
     *
     * @return NOTIFY addresses as array list.
     * @see <a href="https://tools.ietf.org/html/rfc3461#section-4.1">RFC 3461 #4.1</a>
     */
    public String[] getNotify() {
        if (notify.length == 0) {
            String param = verb.getParam("notify");
            if (!param.isEmpty()) {
                notify = param.split(",");
            }
        }

        return notify;
    }

    /**
     * Gets MAIL FROM ORCPT param.
     *
     * @return Original recipient address string.
     * @throws IOException MAIL address parsing problem.
     * @see <a href="https://tools.ietf.org/html/rfc3461#section-4.2">RFC 3461 #4.2</a>
     */
    public InternetAddress getORcpt() throws IOException {
        if (oRcpt == null) {
            String rcpt = verb.getParam("orcpt");
            String[] parts = rcpt.split(";");
            if (parts.length > 1 && parts[0].equalsIgnoreCase("rfc822")) {
                try {
                    oRcpt = new InternetAddress(parts[1]);
                } catch (AddressException e) {
                    throw new IOException(e);
                }
            }
        }

        return oRcpt;
    }

    /**
     * Gets MAIL FROM RET param.
     *
     * @return RET string.
     * @see <a href="https://tools.ietf.org/html/rfc3461#section-4.3">RFC 3461 #4.3</a>
     */
    public String getRet() {
        if (ret.isEmpty()) {
            ret = verb.getParam("ret");
        }

        return ret;
    }

    /**
     * Gets MAIL FROM ENVID param.
     *
     * @return ENVID string.
     * @see <a href="https://tools.ietf.org/html/rfc3461#section-4.4">RFC 3461 #4.4</a>
     */
    public String getEnvId() {
        if (envId.isEmpty()) {
            envId = verb.getParam("envid");
        }

        return envId;
    }
}
