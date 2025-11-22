package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

        saveToDovecotLda(connection, emailParser, config);

        log.debug("Completed Dovecot storage processing for uid={}", connection.getSession().getUID());
        return true;
    }

    /**
     * Save email to Dovecot LDA.
     *
     * @param connection Connection instance.
     * @param emailParser EmailParser instance.
     * @param config     Server configuration.
     * @throws IOException If an I/O error occurs during processing.
     */
    protected void saveToDovecotLda(Connection connection, EmailParser emailParser, ServerConfig config) throws IOException {
        if (!config.getDovecot().getBooleanProperty("saveToDovecotLda")) {
            log.debug("Dovecot LDA storage disabled by configuration (saveToDovecotLda=false). Skipping mailbox delivery.");
            return;
        }

        // Get current envelope and log info.
        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        List<String> originalRecipients = envelope.getRcpts();
        
        // Filter out bot addresses from recipients
        List<String> nonBotRecipients = new ArrayList<>();
        for (String recipient : originalRecipients) {
            if (!envelope.isBotAddress(recipient)) {
                nonBotRecipients.add(recipient);
            } else {
                log.debug("Skipping Dovecot LDA storage for bot address: {}", recipient);
            }
        }
        
        // If all recipients are bot addresses, skip Dovecot LDA processing
        if (nonBotRecipients.isEmpty()) {
            log.debug("All recipients are bot addresses, skipping Dovecot LDA processing");
            return;
        }
        String folder = connection.getSession().isInbound() 
                ? config.getDovecot().getStringProperty("inboxFolder", "INBOX")
                : config.getDovecot().getStringProperty("sentFolder", "Sent");
        log.info("Invoking Dovecot LDA for sender={} recipients={} outbound={} folder={}",
                envelope.getMail(),
                String.join(",", nonBotRecipients),
                connection.getSession().isOutbound(),
                folder);

        // Invoke Dovecot LDA delivery.
        getDovecotLdaClientInstance(connection, emailParser)
                .send();

        // Retrieve transaction results.
        EnvelopeTransactionList envelopeTransactionList = connection.getSession().getSessionTransactionList().getEnvelopes().getLast();
        if (envelopeTransactionList == null) {
            log.error("EnvelopeTransactionList missing after Dovecot LDA send. Treating as failure.");
            throw new IOException("Storage unable to save to Dovecot LDA: no transaction list");
        }

        // Analyze results.
        int failed;
        int requested = 1;
        if (connection.getSession().isOutbound()) {
            failed = envelopeTransactionList.getMail().isError() ? 1 : 0;
        } else {
            failed = envelopeTransactionList.getFailedRecipients().size();
            requested = envelopeTransactionList.getRecipients().size();
        }

        if (failed == 0) {
            log.info("Dovecot LDA delivery successful for mailboxes={}", requested);
            return;
        }

        // There are failures. This only applies to inbound messages.
        if (requested != failed) {
            if (connection.getSession().isInbound()) {
                log.warn("Partial Dovecot LDA delivery failure successCount={} failedCount={} failedRecipients={}",
                        (requested - failed),
                        failed,
                        String.join(",", envelopeTransactionList.getFailedRecipients()));

                // Replace rcpt list with failed recipients for bounce/retry.
                connection.getSession().getEnvelopes().getLast().setRcpts(envelopeTransactionList.getFailedRecipients());
                for (String recipient : connection.getSession().getEnvelopes().getLast().getRcpts()) {
                    processFailure(connection, config, recipient);
                }
            } else {
                log.error("Dovecot LDA delivery failure for outbound message uid={}", connection.getSession().getUID());
                processFailure(connection, config, envelope.getMail());
            }
        } else {
            log.error("Dovecot LDA complete delivery failure");
            throw new IOException("Storage unable to save to Dovecot LDA");
        }
    }

    /**
     * Process delivery failure.
     *
     * @param connection Connection instance.
     * @param config     Server configuration.
     * @param mailbox    The email address of the rejected mailbox.
     */
    protected void processFailure(Connection connection, ServerConfig config, String mailbox) {
        String sender = connection.getSession().getEnvelopes().getLast().getMail();

        // Build the session.
        RelaySession relaySession = new RelaySession(Factories.getSession())
                .setProtocol("esmtp");

        // Create the envelope.
        MessageEnvelope envelope = new MessageEnvelope();
        relaySession.getSession().addEnvelope(envelope);

        BasicConfig dovecotConfig = config.getDovecot();

        // Queue bounce email.
        if (dovecotConfig.hasProperty("failureBehaviour") &&
                dovecotConfig.getStringProperty("failureBehaviour").equalsIgnoreCase("bounce")) {

            BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession().clone()), mailbox);
            envelope.setMail("mailer-daemon@" + config.getHostname())
                    .setRcpt(sender)
                    .setBytes(bounce.getStream().toByteArray());

            log.info("Bouncing rejected mailbox='{}' sender='{}' uid={}", mailbox, sender, connection.getSession().getUID());
        }
        // Queue retry delivery.
        else {
            envelope.setFile(connection.getSession().getEnvelopes().getLast().getFile());
            relaySession.getSession().setDirection(connection.getSession().getDirection());
            relaySession.setProtocol("dovecot-lda");
            relaySession.setMailbox(connection.getSession().isInbound() 
                    ? config.getDovecot().getStringProperty("inboxFolder", "INBOX")
                    : config.getDovecot().getStringProperty("sentFolder", "Sent"));

            // Persist any envelope files (no-op for bytes-only envelopes) before enqueue.
            QueueFiles.persistEnvelopeFiles(relaySession);
        }

        log.debug("Enqueuing for action={}", dovecotConfig.getStringProperty("failureBehaviour"));

        // Queue for retry.
        PersistentQueue.getInstance()
                .enqueue(relaySession);
    }

    /**
     * Get DovecotLdaClient instance.
     * <p>Can be overridden for testing/mocking purposes.
     *
     * @param connection Connection instance.
     * @return DovecotLdaClient instance.
     */
    protected DovecotLdaClient getDovecotLdaClientInstance(Connection connection) {
        ServerConfig config = Config.getServer();
        String folder = connection.getSession().isInbound() 
                ? config.getDovecot().getStringProperty("inboxFolder", "INBOX")
                : config.getDovecot().getStringProperty("sentFolder", "Sent");
        
        RelaySession relaySession = new RelaySession(connection.getSession())
                .setMailbox(folder);

        return new DovecotLdaClient(relaySession);
    }
    
    /**
     * Get DovecotLdaClient instance with chaos headers.
     * <p>Used internally when chaos headers are available.
     *
     * @param connection Connection instance.
     * @param emailParser EmailParser instance for chaos headers.
     * @return DovecotLdaClient instance.
     */
    protected DovecotLdaClient getDovecotLdaClientInstance(Connection connection, EmailParser emailParser) {
        DovecotLdaClient client = getDovecotLdaClientInstance(connection);
        
        // Set chaos headers if enabled.
        if (Config.getServer().isChaosHeaders() && emailParser != null) {
            client.setChaosHeaders(new ChaosHeaders(emailParser));
        }
        
        return client;
    }
}
