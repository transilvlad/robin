package com.mimecast.robin.smtp;

/**
 * A message receipt still being written across several SMTP commands, such as BDAT chunks.
 * <p>Held on its {@link MessageEnvelope} until the last chunk is saved. Anything that abandons
 * the transaction first (RSET, a new MAIL, the end of the session) must abort it so the
 * partial message is discarded instead of leaking.
 */
public interface PendingReceipt {

    /**
     * Discards the partial message. Must not throw; failures are logged.
     */
    void abort();
}
