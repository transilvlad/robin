package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;

import java.io.IOException;

/**
 * Proxy client behaviour.
 * <p>This behaviour is designed for a single envelope proxy connection.
 * <p>It executes the SMTP exchange up to the MAIL command and then waits
 * for individual RCPT calls before accepting the DATA command.
 * <p>Unlike DefaultBehaviour, it does NOT automatically execute the data method.
 */
public class ProxyBehaviour extends DefaultBehaviour {

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
     * <p>Does NOT execute DATA - that must be called separately via sendData().
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
        mail();
    }

    /**
     * Executes MAIL FROM.
     *
     * @throws IOException Unable to communicate.
     */
    void mail() throws IOException {
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
     * Sends RCPT TO for a single recipient.
     * <p>This is called by ServerRcpt for each matching recipient.
     *
     * @param recipient The recipient address.
     * @return The SMTP response from the proxy server.
     * @throws IOException Unable to communicate.
     */
    public String sendRcpt(String recipient) throws IOException {
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
     * Sends DATA command and streams the email.
     * <p>This is called by ServerData when ready to send the message.
     *
     * @return Boolean indicating success.
     * @throws IOException Unable to communicate.
     */
    public boolean sendData() throws IOException {
        if (!mailSent) {
            throw new IOException("Cannot send DATA before MAIL FROM");
        }

        return process("data", connection);
    }

    /**
     * Sends QUIT command.
     *
     * @throws IOException Unable to communicate.
     */
    public void sendQuit() throws IOException {
        quit();
    }
}
