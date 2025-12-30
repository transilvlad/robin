package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.util.Sleep;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @param config      Server configuration.
     * @throws IOException If an I/O error occurs during processing.
     */
    protected void saveToLmtp(Connection connection, EmailParser emailParser, ServerConfig config) throws IOException {
        var lmtpConfig = config.getDovecot().getSaveLmtp();
        List<String> servers = lmtpConfig.getServers();
        int port = lmtpConfig.getPort();
        boolean tls = lmtpConfig.isTls();

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
        log.info("Invoking LMTP delivery for sender={} recipients={} outbound={}",
                envelope.getMail(),
                String.join(",", nonBotRecipients),
                connection.getSession().isOutbound());

        // Get connection pool from Server
        LmtpConnectionPool pool = Server.getLmtpPool();
        if (pool == null) {
            log.error("LMTP connection pool not initialized");
            for (String recipient : nonBotRecipients) {
                processFailure(connection, config, recipient);
            }
            return;
        }

        // Attempt inline delivery with retries.
        long maxAttempts = config.getDovecot().getInlineSaveMaxAttempts();
        int retryDelay = Math.toIntExact(config.getDovecot().getInlineSaveRetryDelay());

        boolean deliverySucceeded = false;

        for (long attempt = 1; attempt <= maxAttempts; attempt++) {
            // Acquire connection permit from pool.
            if (!pool.acquire()) {
                log.error("Failed to acquire LMTP connection permit within timeout");
                break;
            }

            try {
                if (attempt > 1 && retryDelay > 0) {
                    Sleep.nap(retryDelay);
                }

                // Create a new session for LMTP delivery with mx and port from config.
                // No MX resolution needed as servers are provided in config.
                Session lmtpSession = Factories.getSession()
                        .setUID(connection.getSession().getUID())
                        .setMx(servers)  // Use servers list directly - no MX resolution needed.
                        .setPort(port)
                        .setTls(tls)
                        .setLhlo(Config.getServer().getHostname())
                        .setDirection(connection.getSession().getDirection());

                // Clone the envelope with only non-bot recipients.
                MessageEnvelope lmtpEnvelope = envelope.clone();
                lmtpEnvelope.getRcpts().clear();
                lmtpEnvelope.setRcpt(null); // Clear single rcpt field.
                lmtpEnvelope.setRcpts(new ArrayList<>(nonBotRecipients));
                lmtpSession.addEnvelope(lmtpEnvelope);

                // Perform inline LMTP delivery using EmailDelivery.
                new EmailDelivery(lmtpSession).send();

                // Check transaction results to determine success.
                var sessionTransactionList = lmtpSession.getSessionTransactionList();
                var envelopes = sessionTransactionList != null ? sessionTransactionList.getEnvelopes() : null;
                EnvelopeTransactionList transactionList = (envelopes != null && !envelopes.isEmpty())
                        ? envelopes.getLast()
                        : null;

                if (transactionList != null && transactionList.getErrors().isEmpty()) {
                    deliverySucceeded = true;
                    log.info("LMTP delivery successful after {} attempt(s)", attempt);
                    break;
                } else {
                    if (attempt < maxAttempts) {
                        log.warn("Attempt {} of {} LMTP delivery failed (will retry)",
                                attempt, maxAttempts);
                    } else {
                        log.error("Attempt {} of {} LMTP delivery failed (giving up)",
                                attempt, maxAttempts);
                    }
                }
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    log.warn("Attempt {} of {} LMTP delivery threw exception (will retry): {}",
                            attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("Attempt {} of {} LMTP delivery threw exception (giving up): {}",
                            attempt, maxAttempts, e.getMessage());
                }
            } finally {
                // Always release the connection permit.
                pool.release();
            }
        }

        // Process failure if delivery did not succeed.
        if (!deliverySucceeded) {
            for (String recipient : nonBotRecipients) {
                processFailure(connection, config, recipient);
            }
        }
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
