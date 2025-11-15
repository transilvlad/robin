package com.mimecast.robin.smtp;

import com.mimecast.robin.smtp.connection.SmtpException;
import com.mimecast.robin.smtp.extension.client.ProxyBehaviour;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Proxy email delivery class.
 * <p>This class is designed for a single envelope proxy connection.
 * <p>It extends EmailDelivery to enable step-by-step SMTP exchange
 * for proxying individual recipients and data.
 */
public class ProxyEmailDelivery extends EmailDelivery {
    private static final Logger log = LogManager.getLogger(ProxyEmailDelivery.class);

    /**
     * ProxyBehaviour instance.
     */
    private final ProxyBehaviour behaviour;

    /**
     * Flag to track if connection was successful.
     */
    private boolean connected = false;

    /**
     * Constructs a new ProxyEmailDelivery instance with given Session and envelope.
     *
     * @param session  Session instance for the proxy connection.
     * @param envelope MessageEnvelope instance to proxy.
     */
    public ProxyEmailDelivery(Session session, MessageEnvelope envelope) {
        super(session);
        this.behaviour = new ProxyBehaviour(envelope);
    }

    /**
     * Connects and executes SMTP exchange up to MAIL FROM.
     * <p>This establishes the connection and sends EHLO, STARTTLS, AUTH, and MAIL FROM.
     *
     * @return Self.
     * @throws IOException   Unable to communicate.
     * @throws SmtpException SMTP exchange error.
     */
    public ProxyEmailDelivery connect() throws IOException, SmtpException {
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


