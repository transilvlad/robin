package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.storage.PooledLmtpDelivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes claimed relay queue items.
 */
public class RelayDequeue {
    private static final Logger log = LogManager.getLogger(RelayDequeue.class);
    private static final AtomicLong RESCHEDULE_COUNT = new AtomicLong(0);

    private final PersistentQueue<RelaySession> queue;
    private final PooledLmtpDelivery pooledLmtpDelivery;

    public RelayDequeue(PersistentQueue<RelaySession> queue) {
        this(queue, new PooledLmtpDelivery());
    }

    RelayDequeue(PersistentQueue<RelaySession> queue, PooledLmtpDelivery pooledLmtpDelivery) {
        this.queue = queue;
        this.pooledLmtpDelivery = pooledLmtpDelivery;
    }

    /**
     * Processes one claimed queue item.
     */
    public void processClaimedItem(QueueItem<RelaySession> queueItem, long currentEpochSeconds) {
        RelaySession relaySession = queueItem != null ? queueItem.getPayload() : null;
        if (relaySession == null || relaySession.getSession() == null) {
            if (queueItem != null) {
                queue.acknowledge(queueItem.getUid());
            }
            return;
        }

        relaySession.getSession().getSessionTransactionList().clear();
        logSessionInfo(relaySession);
        attemptDelivery(relaySession);

        RelayDeliveryResult result = processDeliveryResults(relaySession);
        log.info("Session processed: uid={}, removedEnvelopes={}, remainingEnvelopes={}",
                relaySession.getSession().getUID(), result.getRemovedCount(), result.getRemainingCount());

        if (relaySession.getSession().getEnvelopes().isEmpty()) {
            queue.acknowledge(queueItem.getUid());
            return;
        }

        if (relaySession.getRetryCount() < relaySession.getMaxRetryCount()) {
            retrySession(queueItem, relaySession, currentEpochSeconds);
            return;
        }

        String lastError = deriveLastError(relaySession);
        if (Config.getServer().getRelay().getBooleanProperty("bounce", true)) {
            generateBounces(relaySession);
        }
        queue.markDead(queueItem.getUid(), lastError);
    }

    boolean isReadyForRetry(RelaySession relaySession, long currentEpochSeconds) {
        int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
        long lastRetryTime = relaySession.getLastRetryTime();
        long nextAllowedTime = lastRetryTime + nextRetrySeconds;
        return currentEpochSeconds >= nextAllowedTime;
    }

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

    void attemptDelivery(RelaySession relaySession) {
        try {
            if ("dovecot-lda".equalsIgnoreCase(relaySession.getProtocol())) {
                new DovecotLdaClient(relaySession).send();
            } else if ("lmtp".equalsIgnoreCase(relaySession.getProtocol())) {
                pooledLmtpDelivery.deliver(relaySession.getSession(), 1, 0);
            } else {
                new EmailDelivery(relaySession.getSession()).send();
            }
        } catch (Exception e) {
            log.error("Delivery failed for session uid={}: {}",
                    relaySession.getSession().getUID(), e.getMessage());
        }
    }

    RelayDeliveryResult processDeliveryResults(RelaySession relaySession) {
        List<EnvelopeTransactionList> transactions =
                relaySession.getSession().getSessionTransactionList().getEnvelopes();
        List<MessageEnvelope> successfulEnvelopes = new ArrayList<>();

        List<MessageEnvelope> envelopes = relaySession.getSession().getEnvelopes();
        if (transactions.size() != envelopes.size()) {
            log.error("Transaction/envelope size mismatch: txCount={}, envCount={}, uid={}",
                    transactions.size(), envelopes.size(), relaySession.getSession().getUID());
        }

        for (int i = 0; i < transactions.size(); i++) {
            EnvelopeTransactionList txList = transactions.get(i);
            MessageEnvelope envelope = envelopes.get(i);

            if (txList.getErrors().isEmpty()) {
                successfulEnvelopes.add(envelope);
            } else {
                List<String> failedRecipients = txList.getFailedRecipients();
                if (failedRecipients != null && !failedRecipients.isEmpty()) {
                    envelope.setRcpts(txList.getFailedRecipients());
                }
            }
        }

        cleanupSuccessfulEnvelopes(successfulEnvelopes);
        relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);

        return new RelayDeliveryResult(
                successfulEnvelopes.size(),
                relaySession.getSession().getEnvelopes().size(),
                successfulEnvelopes
        );
    }

    void cleanupSuccessfulEnvelopes(List<MessageEnvelope> successfulEnvelopes) {
        for (MessageEnvelope envelope : successfulEnvelopes) {
            if (envelope.getFile() != null) {
                Path path = Path.of(envelope.getFile());
                if (Files.exists(path)) {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.error("Failed to delete envelope file: {}, error={}",
                                envelope.getFile(), e.getMessage());
                    }
                }
            }
        }
    }

    void retrySession(QueueItem<RelaySession> queueItem, RelaySession relaySession, long currentEpochSeconds) {
        relaySession.bumpRetryCount();
        int waitSeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
        long nextAttempt = currentEpochSeconds + Math.max(waitSeconds, 0);
        queueItem.setPayload(relaySession).setRetryCount(relaySession.getRetryCount());
        RESCHEDULE_COUNT.incrementAndGet();
        queue.reschedule(queueItem, nextAttempt, deriveLastError(relaySession));
    }

    public static long getRescheduleCount() {
        return RESCHEDULE_COUNT.get();
    }

    void generateBounces(RelaySession relaySession) {
        Set<String> recipients = new LinkedHashSet<>();
        List<MessageEnvelope> remainingEnvelopes = relaySession.getSession().getEnvelopes();
        for (MessageEnvelope envelope : remainingEnvelopes) {
            if (envelope != null && envelope.getRcpts() != null) {
                recipients.addAll(envelope.getRcpts());
            }
        }

        int bounceCount = 0;
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

    void createAndEnqueueBounce(RelaySession originalSession, String recipient) {
        BounceMessageGenerator bounce = new BounceMessageGenerator(originalSession, recipient);
        RelaySession bounceSession = new RelaySession(Factories.getSession()).setProtocol("esmtp");
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("mailer-daemon@" + Config.getServer().getHostname())
                .setRcpt(recipient)
                .setBytes(bounce.getStream().toByteArray());
        bounceSession.getSession().addEnvelope(envelope);
        QueueFiles.persistEnvelopeFiles(bounceSession);
        queue.enqueue(bounceSession);
    }

    private void logSessionInfo(RelaySession relaySession) {
        int envelopesCount = relaySession.getSession().getEnvelopes() != null
                ? relaySession.getSession().getEnvelopes().size() : 0;
        int recipientsCount = countRecipients(relaySession);

        log.info("Processing session: uid={}, protocol={}, retryCount={}, envelopes={}, recipients={}",
                relaySession.getSession().getUID(), relaySession.getProtocol(),
                relaySession.getRetryCount(), envelopesCount, recipientsCount);
    }

    private String deriveLastError(RelaySession relaySession) {
        try {
            if (relaySession.getSession().getSessionTransactionList().getEnvelopes().isEmpty()) {
                return null;
            }
            return relaySession.getRejection();
        } catch (Exception ignored) {
            return null;
        }
    }
}
