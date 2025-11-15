package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RelayDequeue handles the dequeuing and processing of relay sessions from the persistent queue.
 * <p>This class is responsible for:
 * <ul>
 *   <li>Dequeuing relay sessions based on budget constraints</li>
 *   <li>Checking retry timing and re-enqueueing sessions that are not ready</li>
 *   <li>Attempting email delivery via appropriate protocol (SMTP or Dovecot LDA)</li>
 *   <li>Processing delivery results and managing partial failures</li>
 *   <li>Handling retry logic with exponential backoff</li>
 *   <li>Generating bounce messages when max retries are exceeded</li>
 * </ul>
 */
public class RelayDequeue {
    private static final Logger log = LogManager.getLogger(RelayDequeue.class);

    private final PersistentQueue<RelaySession> queue;

    /**
     * Constructs a RelayDequeue processor with the specified queue.
     *
     * @param queue the persistent queue to dequeue relay sessions from
     */
    public RelayDequeue(PersistentQueue<RelaySession> queue) {
        this.queue = queue;
    }

    /**
     * Processes a batch of relay sessions from the queue.
     * <p>This method will attempt to dequeue and process up to {@code maxDequeuePerTick} sessions.
     * Sessions that are not yet ready for retry will be re-enqueued.
     *
     * @param maxDequeuePerTick   the maximum number of sessions to process in this execution
     * @param currentEpochSeconds the current time in epoch seconds
     */
    public void processBatch(int maxDequeuePerTick, long currentEpochSeconds) {
        long queueSize = queue.size();
        int budget = (int) Math.min(maxDequeuePerTick, queueSize);

        if (budget <= 0) {
            log.trace("Queue empty, nothing to process");
            return;
        }

        log.debug("Processing batch: currentEpoch={}, queueSize={}, budget={}",
                currentEpochSeconds, queueSize, budget);

        for (int processed = 0; processed < budget; processed++) {
            RelaySession relaySession = queue.dequeue();
            if (relaySession == null) {
                log.debug("Queue drained early after {} items", processed);
                break;
            }

            processSession(relaySession, currentEpochSeconds);
        }
    }

    /**
     * Processes a single relay session.
     * <p>Checks if the session is ready for retry based on timing, attempts delivery,
     * handles results, and manages re-enqueueing or bounce generation.
     *
     * @param relaySession        the relay session to process
     * @param currentEpochSeconds the current time in epoch seconds
     */
    void processSession(RelaySession relaySession, long currentEpochSeconds) {
        // Check if it's time to retry this session.
        if (!isReadyForRetry(relaySession, currentEpochSeconds)) {
            reEnqueueSession(relaySession, "Too early for retry");
            return;
        }

        log.trace("Clear previous transaction results");
        relaySession.getSession().getSessionTransactionList().clear();

        logSessionInfo(relaySession);

        // Attempt delivery.
        attemptDelivery(relaySession);

        // Process delivery results.
        RelayDeliveryResult result = processDeliveryResults(relaySession);

        log.info("Session processed: uid={}, removedEnvelopes={}, remainingEnvelopes={}",
                relaySession.getSession().getUID(), result.getRemovedCount(), result.getRemainingCount());

        // Handle remaining envelopes (retry or bounce).
        handleRemainingEnvelopes(relaySession, result);
    }

    /**
     * Checks if a relay session is ready for retry based on its retry count and last retry time.
     *
     * @param relaySession        the relay session to check
     * @param currentEpochSeconds the current time in epoch seconds
     * @return true if the session is ready for retry, false otherwise
     */
    boolean isReadyForRetry(RelaySession relaySession, long currentEpochSeconds) {
        int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
        long lastRetryTime = relaySession.getLastRetryTime();
        long nextAllowedTime = lastRetryTime + nextRetrySeconds;

        if (currentEpochSeconds < nextAllowedTime) {
            log.debug("Session not ready for retry: sessionUID={}, retryCount={}, lastRetryTime={}, now={}, nextAllowed={}, backoffSec={}",
                    relaySession.getSession().getUID(), relaySession.getRetryCount(),
                    lastRetryTime, currentEpochSeconds, nextAllowedTime, nextRetrySeconds);
            return false;
        }

        return true;
    }

    /**
     * Logs information about the session being processed.
     *
     * @param relaySession the relay session
     */
    private void logSessionInfo(RelaySession relaySession) {
        int envelopesCount = relaySession.getSession().getEnvelopes() != null
                ? relaySession.getSession().getEnvelopes().size() : 0;
        int recipientsCount = countRecipients(relaySession);

        log.info("Processing session: uid={}, protocol={}, retryCount={}, envelopes={}, recipients={}",
                relaySession.getSession().getUID(), relaySession.getProtocol(),
                relaySession.getRetryCount(), envelopesCount, recipientsCount);
    }

    /**
     * Counts the total number of recipients across all envelopes in the session.
     *
     * @param relaySession the relay session
     * @return the total number of recipients
     */
    int countRecipients(RelaySession relaySession) {
        int count = 0;
        if (relaySession.getSession().getEnvelopes() != null) {
            for (MessageEnvelope envelope : relaySession.getSession().getEnvelopes()) {
                if (envelope != null && envelope.getRcpts() != null) {
                    count += envelope.getRcpts().size();
                }
            }
        }
        return count;
    }

    /**
     * Attempts delivery of the relay session using the appropriate protocol.
     *
     * @param relaySession the relay session to deliver
     */
    void attemptDelivery(RelaySession relaySession) {
        try {
            if ("dovecot-lda".equalsIgnoreCase(relaySession.getProtocol())) {
                log.debug("Attempting Dovecot LDA delivery");
                new DovecotLdaClient(relaySession).send();
            } else {
                log.debug("Attempting email delivery");
                new EmailDelivery(relaySession.getSession()).send();
            }
        } catch (Exception e) {
            log.error("Delivery failed for session uid={}: {}",
                    relaySession.getSession().getUID(), e.getMessage());
        }
    }

    /**
     * Processes the delivery results, removing successful envelopes and updating failed ones.
     *
     * @param relaySession the relay session with delivery results
     * @return a RelayDeliveryResult containing counts and successful envelopes
     */
    RelayDeliveryResult processDeliveryResults(RelaySession relaySession) {
        List<EnvelopeTransactionList> transactions =
                relaySession.getSession().getSessionTransactionList().getEnvelopes();
        List<MessageEnvelope> successfulEnvelopes = new ArrayList<>();

        // Iterate through transactions and identify successful envelopes.
        for (int i = 0; i < transactions.size(); i++) {
            EnvelopeTransactionList txList = transactions.get(i);
            MessageEnvelope envelope = relaySession.getSession().getEnvelopes().get(i);

            if (txList.getErrors().isEmpty()) {
                log.debug("All recipients succeeded");
                successfulEnvelopes.add(envelope);
            } else {
                log.debug("Partial failure: update envelope to contain only failed recipients.");
                if (txList.getRecipients() != txList.getFailedRecipients()) {
                    envelope.setRcpts(txList.getFailedRecipients());
                }
            }
        }

        log.debug("Clean up successful envelopes");
        cleanupSuccessfulEnvelopes(successfulEnvelopes);
        relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);

        int removedCount = successfulEnvelopes.size();
        int remainingCount = relaySession.getSession().getEnvelopes().size();

        return new RelayDeliveryResult(removedCount, remainingCount, successfulEnvelopes);
    }

    /**
     * Deletes the files associated with successful envelopes.
     *
     * @param successfulEnvelopes the list of successfully delivered envelopes
     */
    void cleanupSuccessfulEnvelopes(List<MessageEnvelope> successfulEnvelopes) {
        for (MessageEnvelope envelope : successfulEnvelopes) {
            if (envelope.getFile() != null) {
                Path path = Path.of(envelope.getFile());
                if (Files.exists(path)) {
                    try {
                        Files.delete(path);
                        log.debug("Deleted envelope file: {}", envelope.getFile());
                    } catch (IOException e) {
                        log.error("Failed to delete envelope file: {}, error={}",
                                envelope.getFile(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Handles remaining envelopes that were not successfully delivered.
     * <p>If under max retries, re-enqueues the session. Otherwise, generates bounce messages.
     *
     * @param relaySession the relay session
     * @param result       the delivery result
     */
    void handleRemainingEnvelopes(RelaySession relaySession, RelayDeliveryResult result) {
        if (relaySession.getSession().getEnvelopes().isEmpty()) {
            log.debug("Session fully delivered: uid={}", relaySession.getSession().getUID());
            return;
        }

        if (relaySession.getRetryCount() < relaySession.getMaxRetryCount()) {
            retrySession(relaySession);
        } else if (Config.getServer().getRelay().getBooleanProperty("bounce", true)) {
            generateBounces(relaySession);
        } else {
            log.warn("Max retries reached, but bounce disabled: uid={}", relaySession.getSession().getUID());
        }
    }

    /**
     * Increments the retry count and re-enqueues the session for another attempt.
     *
     * @param relaySession the relay session to retry
     */
    void retrySession(RelaySession relaySession) {
        relaySession.bumpRetryCount();
        log.info("Re-enqueueing for retry: uid={}, newRetryCount={}",
                relaySession.getSession().getUID(), relaySession.getRetryCount());
        reEnqueueSession(relaySession, "retry");
    }

    /**
     * Re-enqueues a session back to the queue after persisting its files.
     *
     * @param relaySession the relay session to re-enqueue
     * @param reason       the reason for re-enqueueing (for logging)
     */
    void reEnqueueSession(RelaySession relaySession, String reason) {
        QueueFiles.persistEnvelopeFiles(relaySession);
        queue.enqueue(relaySession);
        log.debug("Session re-enqueued: uid={}, reason={}",
                relaySession.getSession().getUID(), reason);
    }

    /**
     * Generates bounce messages for all recipients in the remaining envelopes.
     * <p>Called when max retry count has been exceeded.
     *
     * @param relaySession the relay session that has exceeded max retries
     */
    void generateBounces(RelaySession relaySession) {
        int bounceCount = 0;
        List<MessageEnvelope> remainingEnvelopes = relaySession.getSession().getEnvelopes();

        // Process recipients from the last envelope (this matches original logic).
        List<String> recipients = remainingEnvelopes.isEmpty()
                ? List.of()
                : remainingEnvelopes.getLast().getRcpts();

        for (String recipient : recipients) {
            try {
                createAndEnqueueBounce(relaySession, recipient);
                bounceCount++;
            } catch (Exception e) {
                log.error("Failed to generate bounce for recipient {}: {}",
                        recipient, e.getMessage());
            }
        }

        log.warn("Max retries reached: uid={}, generatedBounces={}",
                relaySession.getSession().getUID(), bounceCount);
    }

    /**
     * Creates a bounce message for a failed recipient and enqueues it for delivery.
     *
     * @param originalSession the original relay session that failed
     * @param recipient       the recipient to generate a bounce for
     */
    void createAndEnqueueBounce(RelaySession originalSession, String recipient) {
        // Generate bounce message.
        BounceMessageGenerator bounce = new BounceMessageGenerator(originalSession, recipient);

        // Create new relay session for the bounce.
        RelaySession bounceSession = new RelaySession(Factories.getSession())
                .setProtocol("esmtp");

        // Create envelope for bounce message.
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("mailer-daemon@" + Config.getServer().getHostname())
                .setRcpt(recipient)
                .setBytes(bounce.getStream().toByteArray());

        bounceSession.getSession().addEnvelope(envelope);

        // Persist and enqueue bounce.
        QueueFiles.persistEnvelopeFiles(bounceSession);
        queue.enqueue(bounceSession);

        log.debug("Bounce message enqueued for recipient: {}", recipient);
    }
}
