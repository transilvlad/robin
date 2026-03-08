package com.mimecast.robin.storage;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.sasl.SqlUserLookup;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DovecotStorageProcessor delivers emails to user mailboxes using either LDA or LMTP backends.
 * <p>
 * Backend selection is static and configured via dovecot.json5:
 * <ul>
 *   <li>LDA (Local Delivery Agent): Requires Robin and Dovecot in the same container, uses UNIX socket and binary.</li>
 *   <li>LMTP (Local Mail Transfer Protocol): Default, uses a configurable LMTP server list, works with SQL auth and does not require Robin and Dovecot in the same container.</li>
 * </ul>
 * Backend-specific options are grouped under saveLda and saveLmtp config objects. Shared options are top-level.
 * <p>
 * This processor filters out bot addresses, handles delivery failures, and supports chaos headers for testing.
 * <p>
 * LMTP deliveries use a connection pool to limit concurrent connections and prevent overwhelming Dovecot.
 */
public class DovecotStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(DovecotStorageProcessor.class);
    private final PooledLmtpDelivery pooledLmtpDelivery = new PooledLmtpDelivery();

    /**
     * Processes the email for mailbox storage using the configured backend.
     * <p>
     * Selects LDA or LMTP backend based on which is enabled in dovecot.json5. LMTP takes precedence if both are enabled.
     * Filters out bot addresses and logs all major actions.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if the email is not spam, false if spam is detected.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig config = Config.getServer();

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for Dovecot storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true;
        }

        // Determine backend by checking which one is enabled. LMTP takes precedence if both are enabled.
        if (config.getDovecot().getSaveLmtp().isEnabled()) {
            saveToLmtp(connection, emailParser, config);
        } else if (config.getDovecot().getSaveLda().isEnabled()) {
            saveToLda(connection, emailParser, config);
        } else {
            log.debug("No mailbox delivery backend enabled. Skipping mailbox delivery.");
        }

        log.debug("Completed Dovecot storage processing for uid={}", connection.getSession().getUID());
        return true;
    }

    /**
     * Save email to LMTP backend.
     * <p>
     * Only enabled if saveLmtp.enabled is true. LMTP is the default backend and recommended for distributed setups.
     * Uses a configurable LMTP server list and supports SQL authentication.
     * Implements inline delivery with retry logic matching LDA behavior.
     * Filters out bot addresses and logs delivery actions.
     * <p>
     * Uses a connection pool for efficient connection reuse - TCP connections and LHLO handshakes
     * are reused across deliveries, significantly improving throughput.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @param config      Server configuration.
     * @throws IOException If an I/O error occurs during processing.
     */
    protected void saveToLmtp(Connection connection, EmailParser emailParser, ServerConfig config) throws IOException {
        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        List<String> originalRecipients = envelope.getRcpts();
        List<String> nonBotRecipients = new ArrayList<>();
        for (String recipient : originalRecipients) {
            if (!envelope.isBotAddress(recipient)) {
                nonBotRecipients.add(recipient);
            } else {
                log.debug("Skipping LMTP storage for bot address: {}", recipient);
            }
        }
        if (nonBotRecipients.isEmpty()) {
            log.debug("All recipients are bot addresses, skipping LMTP processing.");
            return;
        }

        // Resolve aliases before delivery so emails go to the correct mailbox.
        nonBotRecipients = resolveAliases(nonBotRecipients);

        log.info("Invoking LMTP delivery for sender={} recipients={} outbound={}",
                envelope.getMail(),
                String.join(",", nonBotRecipients),
                connection.getSession().isOutbound());

        if (!config.getDovecot().getSaveLmtp().isInline()) {
            enqueueLmtpDelivery(connection, envelope, nonBotRecipients, config);
            return;
        }

        // Clone the envelope with only non-bot recipients
        MessageEnvelope lmtpEnvelope = envelope.clone();
        lmtpEnvelope.getRcpts().clear();
        lmtpEnvelope.setRcpt(null);
        lmtpEnvelope.setRcpts(new ArrayList<>(nonBotRecipients));

        connection.getSession().getEnvelopes().clear();
        connection.getSession().addEnvelope(lmtpEnvelope);

        // Attempt inline delivery with retries
        long maxAttempts = config.getDovecot().getInlineSaveMaxAttempts();
        int retryDelay = Math.toIntExact(config.getDovecot().getInlineSaveRetryDelay());
        boolean deliverySucceeded = pooledLmtpDelivery.deliver(connection.getSession(), maxAttempts, retryDelay);
        EnvelopeTransactionList transactionList = connection.getSession().getSessionTransactionList().getEnvelopes().isEmpty()
                ? null
                : connection.getSession().getSessionTransactionList().getEnvelopes().getLast();

        if (deliverySucceeded) {
            log.info("LMTP delivery successful after pooled delivery");
        }

        // Process failure if delivery did not succeed
        if (!deliverySucceeded || transactionList == null) {
            for (String recipient : nonBotRecipients) {
                processFailure(connection, config, recipient);
            }
        }
    }

    private void enqueueLmtpDelivery(Connection connection, MessageEnvelope envelope,
                                     List<String> recipients, ServerConfig config) {
        RelaySession relaySession = new RelaySession(Factories.getSession())
                .setProtocol("lmtp");

        var lmtpConfig = config.getDovecot().getSaveLmtp();
        relaySession.getSession()
                .setDirection(connection.getSession().getDirection())
                .setMx(lmtpConfig.getServers())
                .setPort(lmtpConfig.getPort())
                .setTls(lmtpConfig.isTls())
                .setLhlo(Config.getServer().getHostname());

        MessageEnvelope queuedEnvelope = new MessageEnvelope()
                .setFile(envelope.getFile())
                .setMail(envelope.getMail())
                .setRcpts(new ArrayList<>(recipients));

        relaySession.getSession().addEnvelope(queuedEnvelope);
        QueueFiles.persistEnvelopeFiles(relaySession);
        PersistentQueue.getInstance().enqueue(relaySession);

        log.info("Queued LMTP delivery for sender={} recipients={} uid={}",
                envelope.getMail(),
                String.join(",", recipients),
                connection.getSession().getUID());
    }

    /**
     * Save email to LDA backend.
     * <p>
     * Only enabled if saveLda.enabled is true. Requires Robin and Dovecot in the same container.
     * Filters out bot addresses and handles delivery failures and bounces.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @param config      Server configuration.
     * @throws IOException If an I/O error occurs during processing.
     */
    protected void saveToLda(Connection connection, EmailParser emailParser, ServerConfig config) throws IOException {
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

        // Resolve aliases before delivery so emails go to the correct mailbox.
        nonBotRecipients = resolveAliases(nonBotRecipients);

        String folder = connection.getSession().isInbound()
                ? config.getDovecot().getSaveLda().getInboxFolder()
                : config.getDovecot().getSaveLda().getSentFolder();
        log.info("Invoking Dovecot LDA for sender={} recipients={} outbound={} folder={}",
                envelope.getMail(),
                String.join(",", nonBotRecipients),
                connection.getSession().isOutbound(),
                folder);

        // Invoke Dovecot LDA delivery.
        DovecotLdaClient client = new DovecotLdaClient(
                new RelaySession(connection.getSession())
                        .setMailbox(folder)
        );

        // Set chaos headers if enabled.
        if (Config.getServer().isChaosHeaders() && emailParser != null) {
            client.setChaosHeaders(new ChaosHeaders(emailParser));
        }

        client.send();

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
     * Resolves aliases for a list of recipient addresses using SQL lookup.
     * If no SQL lookup is configured, returns the original list unchanged.
     *
     * @param recipients List of recipient email addresses.
     * @return List with aliases resolved to real destination addresses.
     */
    private List<String> resolveAliases(List<String> recipients) {
        SqlUserLookup lookup = SqlAuthManager.getUserLookup();
        if (lookup == null) {
            return recipients;
        }
        List<String> resolved = new ArrayList<>();
        for (String recipient : recipients) {
            Optional<String> alias = lookup.resolveAlias(recipient);
            if (alias.isPresent()) {
                log.debug("Resolved alias {} -> {}", recipient, alias.get());
                resolved.add(alias.get());
            } else {
                resolved.add(recipient);
            }
        }
        return resolved;
    }

    /**
     * Process delivery failure for a mailbox.
     * <p>
     * Handles bounce or retry logic based on dovecot.json5 'failureBehaviour'.
     * Determines the protocol to queue for retry based on the enabled backend.
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

        var dovecotConfig = config.getDovecot();

        // Queue bounce email.
        if (dovecotConfig.getFailureBehaviour().equalsIgnoreCase("bounce")) {

            BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession().clone()), mailbox);
            envelope.setMail("mailer-daemon@" + config.getHostname())
                    .setRcpt(sender)
                    .setBytes(bounce.getStream().toByteArray());

            log.info("Bouncing rejected mailbox='{}' sender='{}' uid={}", mailbox, sender, connection.getSession().getUID());
        }
        // Queue retry delivery.
        else {
            envelope.setFile(connection.getSession().getEnvelopes().getLast().getFile());
            envelope.setMail(sender); // Set sender.
            envelope.setRcpts(new ArrayList<>(List.of(mailbox))); // Set recipient.
            relaySession.getSession().setDirection(connection.getSession().getDirection());

            // Determine protocol to queue based on enabled backend: LMTP takes precedence.
            if (dovecotConfig.getSaveLmtp().isEnabled()) {
                relaySession.setProtocol("lmtp");
                // Set LMTP servers and port directly to avoid MX resolution.
                var lmtpConfig = dovecotConfig.getSaveLmtp();
                relaySession.getSession()
                        .setMx(lmtpConfig.getServers())
                        .setPort(lmtpConfig.getPort())
                        .setTls(lmtpConfig.isTls())
                        .setLhlo(Config.getServer().getHostname());
            } else if (dovecotConfig.getSaveLda().isEnabled()) {
                relaySession.setProtocol("dovecot-lda");
                relaySession.setMailbox(connection.getSession().isInbound()
                        ? dovecotConfig.getSaveLda().getInboxFolder()
                        : dovecotConfig.getSaveLda().getSentFolder());
            }

            // Persist any envelope files (no-op for bytes-only envelopes) before enqueue.
            QueueFiles.persistEnvelopeFiles(relaySession);
        }

        log.debug("Enqueuing for action={}", dovecotConfig.getFailureBehaviour());

        // Queue for retry.
        PersistentQueue.getInstance()
                .enqueue(relaySession);
    }
}
