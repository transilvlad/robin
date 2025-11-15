package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Proxy client behaviour.
 * <p>This behaviour is designed for a single envelope proxy connection.
 * <p>It executes the SMTP exchange up to the MAIL command and then waits
 * for individual RCPT calls before accepting the DATA command.
 * <p>Unlike DefaultBehaviour, it does NOT automatically execute the data method.
 */
public class ProxyBehaviour implements Behaviour {
    protected static final Logger log = LogManager.getLogger(ProxyBehaviour.class);

    /**
     * Connection.
     */
    Connection connection;

    /**
     * Envelope being proxied.
     */
    MessageEnvelope envelope;

    /**
     * Flag to track if MAIL FROM was sent.
     */
    private boolean mailSent = false;

    /**
     * Constructs a new ProxyBehaviour instance.
     *
     * @param envelope The envelope to proxy.
     */
    public ProxyBehaviour(MessageEnvelope envelope) {
        this.envelope = envelope;
    }

    /**
     * Executes delivery up to MAIL FROM.
     * <p>Does NOT execute DATA - that must be called separately via processData().
     *
     * @param connection Connection instance.
     * @throws IOException Unable to communicate.
     */
    @Override
    public void process(Connection connection) throws IOException {
        this.connection = connection;

        if (!ehlo()) return;
        if (!startTls()) return;
        if (!auth()) return;
        
        // Send MAIL FROM for the envelope.
        sendMail();
    }

    /**
     * Executes EHLO.
     *
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    boolean ehlo() throws IOException {
        // HELO/LHLO/EHLO
        if (connection.getSession().getHelo() != null ||
                connection.getSession().getLhlo() != null ||
                connection.getSession().getEhlo() != null) {
            return process("ehlo", connection);
        }

        return false;
    }

    /**
     * Executes STARTTLS.
     *
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    boolean startTls() throws IOException {
        return !process("starttls", connection) || !connection.getSession().isStartTls() || ehlo();
    }

    /**
     * Executes AUTH.
     *
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean auth() throws IOException {
        // Authenticate if configured.
        if (connection.getSession().isAuth()) {
            if (!process("auth", connection)) return false;

            // Start TLS if specifically configured to do after AUTH.
            if (connection.getSession().isAuthBeforeTls()) {
                return startTls();
            }
        }

        return true;
    }

    /**
     * Executes MAIL FROM.
     *
     * @throws IOException Unable to communicate.
     */
    void sendMail() throws IOException {
        if (!mailSent) {
            // Ensure envelope is in session for ClientMail to process.
            if (!connection.getSession().getEnvelopes().contains(envelope)) {
                connection.getSession().addEnvelope(envelope);
            }
            
            if (process("mail", connection)) {
                mailSent = true;
                log.debug("MAIL FROM sent successfully for proxy connection");
            }
        }
    }

    /**
     * Executes RCPT TO for a single recipient.
     * <p>This is called by ServerRcpt for each matching recipient.
     *
     * @param recipient The recipient address.
     * @return The SMTP response from the proxy server.
     * @throws IOException Unable to communicate.
     */
    public String processRcpt(String recipient) throws IOException {
        if (!mailSent) {
            throw new IOException("Cannot send RCPT before MAIL FROM");
        }

        // Temporarily add this recipient to the envelope for processing.
        String write = "RCPT TO:<" + recipient + ">" + envelope.getParams("rcpt");
        connection.write(write);

        String read = connection.read("250");
        
        // Add to transaction list.
        int envelopeIndex = connection.getSession().getSessionTransactionList().getEnvelopes().size() - 1;
        if (envelopeIndex >= 0) {
            connection.getSession().getSessionTransactionList()
                .getEnvelopes().get(envelopeIndex)
                .addTransaction("RCPT", write, read, !read.startsWith("250"));
        }

        log.debug("RCPT TO sent for recipient: {} with response: {}", recipient, read);
        return read;
    }

    /**
     * Executes DATA command and streams the email.
     * <p>This is called by ServerData when ready to send the message.
     *
     * @return Boolean indicating success.
     * @throws IOException Unable to communicate.
     */
    public boolean processData() throws IOException {
        if (!mailSent) {
            throw new IOException("Cannot send DATA before MAIL FROM");
        }

        return process("data", connection);
    }

    /**
     * Executes QUIT.
     *
     * @throws IOException Unable to communicate.
     */
    public void quit() throws IOException {
        process("quit", connection);
    }

    /**
     * Processes extension.
     *
     * @param extension  String.
     * @param connection Connection instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    boolean process(String extension, Connection connection) throws IOException {
        java.util.Optional<com.mimecast.robin.smtp.extension.Extension> opt = 
            com.mimecast.robin.main.Extensions.getExtension(extension);
        return opt.isPresent() && opt.get().getClient().process(connection);
    }
}
