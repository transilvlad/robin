package com.mimecast.robin.storage;

import com.mimecast.robin.main.Extensions;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.Extension;
import com.mimecast.robin.smtp.extension.client.Behaviour;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * LMTP-specific client behaviour for connection pooling.
 * <p>
 * Unlike DefaultBehaviour, this class is designed for connection reuse:
 * <ul>
 *   <li>LHLO is sent only once per connection (when created by the pool)</li>
 *   <li>MAIL/RCPT/DATA are sent for each delivery</li>
 *   <li>QUIT is NOT sent after delivery (connection returned to pool)</li>
 *   <li>QUIT is sent only when the pool closes the connection</li>
 * </ul>
 * <p>
 * LMTP (RFC 2033) uses LHLO instead of EHLO and is designed for local mail delivery
 * where authentication and TLS are typically not required.
 */
public class LmtpBehaviour implements Behaviour {
    private static final Logger log = LogManager.getLogger(LmtpBehaviour.class);

    /**
     * Process delivery for a pooled connection.
     * <p>
     * This method is called for each email delivery. It assumes LHLO has already
     * been done (when the connection was created) and skips QUIT (so the connection
     * can be reused).
     *
     * @param connection Connection instance.
     * @throws IOException If communication fails.
     */
    @Override
    public void process(Connection connection) throws IOException {
        // Connection is already established with LHLO done.
        // Just send the envelope (MAIL, RCPT, DATA).
        send(connection);
        // No QUIT - connection will be returned to pool.
    }

    /**
     * Performs LHLO handshake for a new connection.
     * <p>
     * Called by the pool when creating a new connection. This should only
     * be called once per connection lifetime.
     *
     * @param connection Connection instance.
     * @return True if LHLO succeeded, false otherwise.
     * @throws IOException If communication fails.
     */
    public boolean lhlo(Connection connection) throws IOException {
        // LHLO (LMTP uses LHLO instead of EHLO)
        if (connection.getSession().getLhlo() != null) {
            return process("ehlo", connection); // ClientEhlo handles LHLO
        }
        log.error("No LHLO hostname configured");
        return false;
    }

    /**
     * Sends the envelope (MAIL, RCPT, DATA).
     *
     * @param connection Connection instance.
     * @throws IOException If communication fails.
     */
    private void send(Connection connection) throws IOException {
        if (!process("mail", connection)) return;
        if (!process("rcpt", connection)) return;
        process("data", connection);
    }

    /**
     * Sends QUIT command to gracefully close the connection.
     * <p>
     * Called by the pool before closing a connection.
     *
     * @param connection Connection instance.
     */
    public void quit(Connection connection) {
        try {
            process("quit", connection);
        } catch (IOException e) {
            log.debug("QUIT failed (connection may already be closed): {}", e.getMessage());
        }
    }

    /**
     * Processes an extension (SMTP command).
     *
     * @param extension  Extension name (e.g., "mail", "rcpt", "data").
     * @param connection Connection instance.
     * @return True if successful, false otherwise.
     * @throws IOException If communication fails.
     */
    private boolean process(String extension, Connection connection) throws IOException {
        Optional<Extension> opt = Extensions.getExtension(extension);
        return opt.isPresent() && opt.get().getClient().process(connection);
    }
}
