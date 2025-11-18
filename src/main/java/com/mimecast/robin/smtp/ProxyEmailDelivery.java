package com.mimecast.robin.smtp;

import com.mimecast.robin.config.server.ProxyRule;
import com.mimecast.robin.smtp.connection.SmtpException;
import com.mimecast.robin.smtp.extension.client.ProxyBehaviour;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Proxy email delivery class.
 * <p>This class supports MULTIPLE envelopes per connection through connection reuse.
 * <p>It extends EmailDelivery to enable step-by-step SMTP exchange
 * for proxying individual recipients and data.
 * <p><b>Connection reuse design:</b> This connection can proxy multiple envelopes:
 * <ul>
 *     <li>Connection established on first matching recipient</li>
 *     <li>After DATA, connection remains open for next envelope</li>
 *     <li>Each new envelope calls prepareForEnvelope() to reset state</li>
 *     <li>Connection closed only when session ends (EmailReceipt finally block)</li>
 *     <li>Significantly improves performance by avoiding connection overhead</li>
 * </ul>
 * <p>This design enables efficient proxy forwarding for high-volume scenarios
 * where multiple messages match the same proxy rule within a single SMTP session.
 */
public class ProxyEmailDelivery extends EmailDelivery {
    private static final Logger log = LogManager.getLogger(ProxyEmailDelivery.class);

    /**
     * ProxyBehaviour instance.
     */
    private ProxyBehaviour behaviour;

    /**
     * ProxyRule instance.
     */
    private final ProxyRule rule;

    /**
     * Current envelope being processed.
     */
    private MessageEnvelope currentEnvelope;

    /**
     * Flag to track if connection was successful.
     */
    private boolean connected = false;

    /**
     * Constructs a new ProxyEmailDelivery instance with given Session and envelope.
     *
     * @param session  Session instance for the proxy connection.
     * @param envelope MessageEnvelope instance to proxy.
     * @param rule     ProxyRule instance with configuration.
     */
    public ProxyEmailDelivery(Session session, MessageEnvelope envelope, ProxyRule rule) {
        super(session);
        this.currentEnvelope = envelope;
        this.behaviour = new ProxyBehaviour(envelope);
        this.rule = rule;
    }

    /**
     * Prepares the connection for a new envelope.
     * <p>This method must be called when reusing the connection for a subsequent envelope.
     * <p>It resets the ProxyBehaviour state to send MAIL FROM for the new envelope.
     *
     * @param envelope The new envelope to proxy.
     */
    public void prepareForEnvelope(MessageEnvelope envelope) {
        if (envelope != this.currentEnvelope) {
            this.currentEnvelope = envelope;
            this.behaviour = new ProxyBehaviour(envelope);
            log.debug("Prepared proxy connection for new envelope");
        }
    }

    /**
     * Checks if this connection is for the given envelope.
     *
     * @param envelope Envelope to check.
     * @return true if this connection is handling the given envelope.
     */
    public boolean isForCurrentEnvelope(MessageEnvelope envelope) {
        return this.currentEnvelope == envelope;
    }

    /**
     * Connects and executes SMTP exchange up to MAIL FROM.
     * <p>This establishes the connection and sends EHLO, STARTTLS, AUTH, and MAIL FROM.
     * <p>If already connected (from previous envelope), skips connection and just sends MAIL FROM.
     *
     * @return Self.
     * @throws IOException   Unable to communicate.
     * @throws SmtpException SMTP exchange error.
     */
    public ProxyEmailDelivery connect() throws IOException, SmtpException {
        // If already connected, just prepare for new envelope.
        if (connected) {
            log.debug("Reusing existing proxy connection");
            try {
                behaviour.process(connection);
                log.debug("Proxy MAIL FROM sent for new envelope");
            } catch (IOException e) {
                log.warn("Error sending MAIL FROM on existing connection: {}", e.getMessage());
                connected = false;
                throw e;
            }
            return this;
        }

        // Establish new connection.
        try {
            connection.connect();
            log.debug("Proxy connection established");

            behaviour.process(connection);
            connected = true;
            log.debug("Proxy MAIL FROM sent successfully");

        } catch (SmtpException e) {
            log.warn("SMTP error in proxy connection: {}", e.getMessage());
            close();
            throw e;

        } catch (IOException e) {
            log.warn("IO error in proxy connection: {}", e.getMessage());
            connection.getSession().getSessionTransactionList().addTransaction("SMTP", "101 " + e.getMessage(), true);
            close();
            throw e;
        }

        return this;
    }

    /**
     * Sends RCPT TO for a single recipient.
     *
     * @param recipient The recipient address.
     * @return The SMTP response from the proxy server.
     * @throws IOException Unable to communicate.
     */
    public String sendRcpt(String recipient) throws IOException {
        if (!connected) {
            throw new IOException("Cannot send RCPT: not connected");
        }
        return behaviour.sendRcpt(recipient);
    }

    /**
     * Sends DATA command and streams the email.
     *
     * @return true if successful, false otherwise.
     * @throws IOException Unable to communicate.
     */
    public boolean sendData() throws IOException {
        if (!connected) {
            throw new IOException("Cannot send DATA: not connected");
        }
        return behaviour.sendData();
    }

    /**
     * Closes the proxy connection.
     */
    public void close() {
        if (connected) {
            try {
                // Call quit method from ProxyBehaviour which calls the parent's quit
                behaviour.sendQuit();
            } catch (IOException e) {
                log.debug("Error sending QUIT: {}", e.getMessage());
            }
        }
        connection.close();
        connected = false;
        log.debug("Proxy connection closed");
    }

    /**
     * Checks if the connection is established and ready.
     *
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return connected;
    }
}


