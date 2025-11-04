package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Dovecot storage processor for mailbox storage.
 */
public class DovecotStorageProcessor implements StorageProcessor {
    private static final Logger log = LogManager.getLogger(DovecotStorageProcessor.class);

    /**
     * Processes the email for mailbox storage.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if the email is not spam, false if spam is detected.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    public boolean process(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig config = Config.getServer();

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for Dovecot storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true; // Nothing to do, not an error.
        }

        saveToDovecotLda(connection, config);

        log.debug("Completed Dovecot storage processing for uid={}", connection.getSession().getUID());
        return true;
    }

    /**
     * Process rejected recipient.
     *
     * @param connection Connection instance.
     * @param config     Server configuration.
     * @throws IOException If an I/O error occurs during processing.
     */
    protected void saveToDovecotLda(Connection connection, ServerConfig config) throws IOException {
        if (!config.getDovecot().getBooleanProperty("saveToDovecotLda")) {
            log.debug("Dovecot LDA storage disabled by configuration (saveToDovecotLda=false). Skipping mailbox delivery.");
            return;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        List<String> originalRecipients = envelope.getRcpts();
        log.info("Invoking Dovecot LDA for uid={} sender={} recipients={} outbound={} mailboxHint={}",
                connection.getSession().getUID(),
                envelope.getMail(),
                String.join(",", originalRecipients),
                connection.getSession().isOutbound(),
                config.getDovecot().getStringProperty("outboundMailbox", "Sent"));

        getDovecotLdaDeliveryInstance(connection, config.getDovecot()).send();

        EnvelopeTransactionList envelopeTransactionList = connection.getSession().getSessionTransactionList().getEnvelopes().getLast();
        if (envelopeTransactionList == null) {
            log.error("EnvelopeTransactionList missing after Dovecot LDA send (uid={}). Treating as failure.", connection.getSession().getUID());
            throw new IOException("Storage unable to save to Dovecot LDA: no transaction list");
        }

        int failed = envelopeTransactionList.getFailedRecipients().size();
        int requested = envelopeTransactionList.getRecipients().size();
        if (failed == 0) {
            log.info("Dovecot LDA delivery successful for all {} recipient(s) uid={}", requested, connection.getSession().getUID());
            return;
        }

        // There are failures.
        if (requested != failed) {
            log.warn("Partial Dovecot LDA delivery failure uid={} successCount={} failedCount={} failedRecipients={}",
                    connection.getSession().getUID(),
                    (requested - failed),
                    failed,
                    String.join(",", envelopeTransactionList.getFailedRecipients()));

            // Replace rcpt list with failed recipients for bounce handling.
            connection.getSession().getEnvelopes().getLast().setRcpts(envelopeTransactionList.getFailedRecipients());
            for (String recipient : connection.getSession().getEnvelopes().getLast().getRcpts()) {
                processRejectedRecipient(connection, config, recipient);
            }
        } else {
            log.error("All {} recipient(s) failed Dovecot LDA delivery uid={} recipients={}", failed, connection.getSession().getUID(), String.join(",", originalRecipients));
            throw new IOException("Storage unable to save to Dovecot LDA");
        }
    }

    /**
     * Process rejected recipient.
     *
     * @param connection Connection instance.
     * @param config     Server configuration.
     * @param recipient  The email address of the rejected recipient.
     */
    protected void processRejectedRecipient(Connection connection, ServerConfig config, String recipient) {
        String sender = connection.getSession().getEnvelopes().getLast().getMail();
        log.info("Bouncing rejected recipient='{}' sender='{}' uid={}", recipient, sender, connection.getSession().getUID());

        // Generate bounce email.
        BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession()), recipient);

        // Build the session.
        RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                .setProtocol("esmtp");

        // Create the envelope.
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("mailer-daemon@" + config.getHostname())
                .setRcpt(sender)
                .setBytes(bounce.getStream().toByteArray());
        relaySessionBounce.getSession().addEnvelope(envelope);

        // Queue bounce for delivery using runtime-configured queue file (fallback to default).
        File queueFile = new File(config.getQueue().getStringProperty(
                "queueFile",
                RelayQueueCron.QUEUE_FILE.getAbsolutePath()
        ));

        // Persist any envelope files (no-op for bytes-only envelopes) before enqueue.
        QueueFiles.persistEnvelopeFiles(relaySessionBounce);

        log.debug("Enqueuing bounce message for rejected recipient='{}' queueFile={} size={}B", recipient, queueFile.getAbsolutePath(), bounce.getStream().size());
        PersistentQueue.getInstance(queueFile)
                .enqueue(relaySessionBounce);
    }

    /**
     * Get DovecotLdaDelivery instance.
     * <p>Can be overridden for testing/mocking purposes.
     *
     * @param connection    Connection instance.
     * @param dovecotConfig Dovecot configuration.`
     * @return DovecotLdaDelivery instance.
     */
    protected DovecotLdaDelivery getDovecotLdaDeliveryInstance(Connection connection, BasicConfig dovecotConfig) {
        RelaySession relaySession = new RelaySession(connection.getSession());
        if (connection.getSession().isOutbound()) {
            relaySession.setMailbox(dovecotConfig.getStringProperty("outboundMailbox", "Sent"));
        }

        return new DovecotLdaDelivery(relaySession);
    }
}
