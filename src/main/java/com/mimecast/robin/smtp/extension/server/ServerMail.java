package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.verb.Verb;
import org.apache.commons.lang3.StringUtils;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.Optional;

/**
 * MAIL extension processor.
 *
 * @see <a href="https://tools.ietf.org/html/rfc1870">RFC 1870</a>
 * @see <a href="https://tools.ietf.org/html/rfc3030">RFC 3030</a>
 * @see <a href="https://tools.ietf.org/html/rfc3461">RFC 3461</a>
 * @see <a href="https://tools.ietf.org/html/rfc6152">RFC 6152</a>
 */
public class ServerMail extends ServerProcessor {

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
            if (connection.getSession().isAuth() && connection.getSession().getDirection() == Session.Direction.OUTBOUND) {
                connection.write(String.format(SmtpResponses.AUTH_REQUIRED_530, connection.getSession().getUID()));
                return true;
            }

            // Check envelope limit before adding new envelope.
            if (connection.getSession().getEnvelopes().size() >= envelopeLimit) {
                connection.write(String.format(SmtpResponses.ENVELOPE_LIMIT_EXCEEDED_452, connection.getSession().getUID()));
                return true;

            }

            // Make envelope.
            MessageEnvelope envelope = new MessageEnvelope();
            connection.getSession().addEnvelope(envelope);

            // ScenarioConfig response.
            Optional<ScenarioConfig> opt = connection.getScenario();
            if (opt.isPresent() && opt.get().getMail() != null) {
                if (opt.get().getMail().startsWith("2")) {
                    envelope.setMail(getAddress().getAddress());
                }
                connection.write(opt.get().getMail());
            }

            // Accept all.
            else {
                envelope.setMail(getAddress().getAddress());
                connection.write(String.format(SmtpResponses.SENDER_OK_250, connection.getSession().getUID()));
            }

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
     * Gets MAIL FROM address.
     *
     * @return Address instance.
     * @throws IOException MAIL address parsing problem.
     */
    public InternetAddress getAddress() throws IOException {
        if (address == null) {
            try {
                String from = verb.getParam("from").replace("<>", "");
                address = StringUtils.isNotBlank(from) ? new InternetAddress(from) : new InternetAddress();
            } catch (AddressException e) {
                throw new IOException(e);
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
