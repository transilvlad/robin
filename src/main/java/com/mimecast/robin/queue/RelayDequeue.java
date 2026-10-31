package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mx.MXDelivery;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaClient;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.storage.StalwartDirectDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.transaction.Transaction;
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
    private final StalwartDirectDelivery stalwartDirectDelivery;

    public RelayDequeue(PersistentQueue<RelaySession> queue) {
        this(queue, new PooledLmtpDelivery(), new StalwartDirectDelivery());
    }

    RelayDequeue(PersistentQueue<RelaySession> queue, PooledLmtpDelivery pooledLmtpDelivery) {
        this(queue, pooledLmtpDelivery, new StalwartDirectDelivery());
    }

    RelayDequeue(PersistentQueue<RelaySession> queue, PooledLmtpDelivery pooledLmtpDelivery,
                 StalwartDirectDelivery stalwartDirectDelivery) {
        this.queue = queue;
        this.pooledLmtpDelivery = pooledLmtpDelivery;
        this.stalwartDirectDelivery = stalwartDirectDelivery;
    }

    /**
     * Processes one claimed queue item and returns a deferred mutation result.
     */
    public RelayQueueWorkResult processClaimedItem(QueueItem<RelaySession> queueItem, long currentEpochSeconds) {
        RelaySession relaySession = queueItem != null ? queueItem.getPayload() : null;
        if (queueItem == null) {
            return new RelayQueueWorkResult(null, List.of(), List.of());
        }
        if (relaySession == null || relaySession.getSession() == null) {
            return new RelayQueueWorkResult(QueueMutation.acknowledge(queueItem), List.of(), List.of());
        }

        relaySession.getSession().getSessionTransactionList().clear();
        long maximumAge = RetryScheduler.getMaxAgeSeconds();
        if (maximumAge > 0 && currentEpochSeconds - queueItem.getCreatedAtEpochSeconds() >= maximumAge) {
            String error = "554 5.4.7 Delivery time expired";
            relaySession.getSession().getSessionTransactionList().addTransaction("QUEUE", error, true);
            return deadSession(queueItem, relaySession, List.of(), error);
        }
        logSessionInfo(relaySession);
        attemptDelivery(relaySession);

        RelayDeliveryResult result = processDeliveryResults(relaySession);
        List<Path> cleanupPaths = collectCleanupPaths(result.getSuccessfulEnvelopes());
        log.info("Session processed: uid={}, removedEnvelopes={}, remainingEnvelopes={}",
                relaySession.getSession().getUID(), result.getRemovedCount(), result.getRemainingCount());

        queueItem.setPayload(relaySession).setRetryCount(relaySession.getRetryCount());

        if (relaySession.getSession().getEnvelopes().isEmpty()) {
            return new RelayQueueWorkResult(QueueMutation.acknowledge(queueItem), List.of(), cleanupPaths);
        }

        if (hasPermanentFailuresOnly(relaySession)) {
            String lastError = deriveLastError(relaySession);
            log.warn("Permanent 5xx failure, marking dead: uid={}", relaySession.getSession().getUID());
            return deadSession(queueItem, relaySession, cleanupPaths, lastError);
        }

        if (relaySession.getRetryCount() < relaySession.getMaxRetryCount()
                && relaySession.getRetryCount() < RetryScheduler.getTotalRetries()) {
            return retrySession(queueItem, relaySession, currentEpochSeconds, cleanupPaths);
        }

        String lastError = deriveLastError(relaySession);
        return deadSession(queueItem, relaySession, cleanupPaths, lastError);
    }

    private RelayQueueWorkResult deadSession(QueueItem<RelaySession> item, RelaySession session,
                                            List<Path> cleanupPaths, String error) {
        List<RelaySession> bounces = Config.getServer().getRelay().getBooleanProperty("bounce", true)
                ? generateBounces(session) : List.of();
        item.setPayload(session).setRetryCount(session.getRetryCount()).dead(error);
        return new RelayQueueWorkResult(QueueMutation.dead(item, error), bounces, cleanupPaths);
    }

    boolean isReadyForRetry(RelaySession relaySession, long currentEpochSeconds) {
        int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
        if (nextRetrySeconds < 0) {
            return false;
        }
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
            } else if ("stalwart-direct".equalsIgnoreCase(relaySession.getProtocol())) {
                stalwartDirectDelivery.deliver(relaySession.getSession(), 1, 0);
            } else if ("mx".equalsIgnoreCase(relaySession.getProtocol())) {
                new MXDelivery().deliver(relaySession.getSession());
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

        for (int i = 0; i < Math.min(transactions.size(), envelopes.size()); i++) {
            EnvelopeTransactionList txList = transactions.get(i);
            MessageEnvelope envelope = envelopes.get(i);

            boolean smtp = "esmtp".equalsIgnoreCase(relaySession.getProtocol())
                    || "mx".equalsIgnoreCase(relaySession.getProtocol());
            boolean committed = !smtp || isMessageCommitted(txList);
            if (committed && txList.getErrors().isEmpty()) {
                successfulEnvelopes.add(envelope);
            } else {
                List<String> failedRecipients = txList.getFailedRecipients();
                if (committed && failedRecipients != null && !failedRecipients.isEmpty()) {
                    envelope.setRcpts(txList.getFailedRecipients());
                }
            }
        }

        relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);

        return new RelayDeliveryResult(
                successfulEnvelopes.size(),
                relaySession.getSession().getEnvelopes().size(),
                successfulEnvelopes
        );
    }

    private boolean isMessageCommitted(EnvelopeTransactionList transactions) {
        Transaction data = transactions.getData();
        if (data != null && !data.isError() && data.getResponseCode().startsWith("2")) {
            return true;
        }
        List<Transaction> chunks = transactions.getBdat();
        return !chunks.isEmpty() && chunks.stream().noneMatch(Transaction::isError)
                && chunks.getLast().getPayload() != null
                && chunks.getLast().getPayload().toUpperCase(java.util.Locale.ROOT).contains(" LAST")
                && chunks.getLast().getResponseCode().startsWith("2");
    }

    List<Path> collectCleanupPaths(List<MessageEnvelope> successfulEnvelopes) {
        List<Path> paths = new ArrayList<>();
        for (MessageEnvelope envelope : successfulEnvelopes) {
            if (envelope != null && envelope.getFile() != null) {
                paths.add(Path.of(envelope.getFile()));
            }
        }
        return paths;
    }

    void cleanupSuccessfulEnvelopes(List<MessageEnvelope> successfulEnvelopes) {
        deleteEnvelopeFiles(collectCleanupPaths(successfulEnvelopes));
    }

    void deleteEnvelopeFiles(List<Path> paths) {
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.error("Failed to delete envelope file: {}, error={}", path, e.getMessage());
            }
        }
    }

    RelayQueueWorkResult retrySession(QueueItem<RelaySession> queueItem, RelaySession relaySession,
                                      long currentEpochSeconds, List<Path> cleanupPaths) {
        if (relaySession.getRetryCount() >= relaySession.getMaxRetryCount()
                || relaySession.getRetryCount() >= RetryScheduler.getTotalRetries()) {
            return deadSession(queueItem, relaySession, cleanupPaths, deriveLastError(relaySession));
        }
        relaySession.bumpRetryCount();
        int waitSeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
        if (waitSeconds < 0) {
            return deadSession(queueItem, relaySession, cleanupPaths, deriveLastError(relaySession));
        }
        long nextAttempt = currentEpochSeconds + waitSeconds;
        long maximumAge = RetryScheduler.getMaxAgeSeconds();
        if (maximumAge > 0) {
            long age = Math.max(0, currentEpochSeconds - queueItem.getCreatedAtEpochSeconds());
            nextAttempt = currentEpochSeconds + Math.min(waitSeconds, Math.max(0, maximumAge - age));
        }
        queueItem.setPayload(relaySession).setRetryCount(relaySession.getRetryCount());
        RESCHEDULE_COUNT.incrementAndGet();
        return new RelayQueueWorkResult(
                QueueMutation.reschedule(queueItem, nextAttempt, deriveLastError(relaySession)),
                List.of(),
                cleanupPaths
        );
    }

    public static long getRescheduleCount() {
        return RESCHEDULE_COUNT.get();
    }

    List<RelaySession> generateBounces(RelaySession relaySession) {
        Set<String> recipients = new LinkedHashSet<>();
        List<MessageEnvelope> remainingEnvelopes = relaySession.getSession().getEnvelopes();
        for (MessageEnvelope envelope : remainingEnvelopes) {
            if (envelope != null && envelope.getRcpts() != null) {
                recipients.addAll(envelope.getRcpts());
            }
        }

        List<RelaySession> bounces = new ArrayList<>();
        for (String recipient : recipients) {
            RelaySession bounceSession = createBounceSession(relaySession, recipient);
            if (bounceSession != null) {
                bounces.add(bounceSession);
            }
        }

        log.warn("Max retries reached: uid={}, generatedBounces={}",
                relaySession.getSession().getUID(), bounces.size());
        return bounces;
    }

    RelaySession createBounceSession(RelaySession originalSession, String recipient) {
        BounceMessageGenerator bounce = new BounceMessageGenerator(originalSession, recipient);
        RelaySession bounceSession = bounce.toRelaySession();
        if (bounceSession == null) {
            return null;
        }
        if (!QueueFiles.persistEnvelopeFiles(bounceSession)) {
            throw new IllegalStateException("Failed to persist delivery status notification before enqueue");
        }
        return bounceSession;
    }

    /**
     * Checks if all errors are permanent 5xx SMTP failures (ESMTP protocol only).
     * When true, the item should be marked dead without retry.
     *
     * @param relaySession The relay session to check.
     * @return True if all failures are permanent 5xx.
     */
    boolean hasPermanentFailuresOnly(RelaySession relaySession) {
        if (!"esmtp".equalsIgnoreCase(relaySession.getProtocol())
                && !"mx".equalsIgnoreCase(relaySession.getProtocol())) {
            return false;
        }
        boolean hasErrors = false;
        for (Transaction error : relaySession.getSession().getSessionTransactionList().getErrors()) {
            hasErrors = true;
            if (!error.getResponseCode().startsWith("5")) {
                return false;
            }
        }
        for (EnvelopeTransactionList txList :
                relaySession.getSession().getSessionTransactionList().getEnvelopes()) {
            for (Transaction error : txList.getErrors()) {
                hasErrors = true;
                if (!error.getResponseCode().startsWith("5")) {
                    return false;
                }
            }
        }
        return hasErrors;
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
        var transactions = relaySession.getSession().getSessionTransactionList();
        for (int i = transactions.getEnvelopes().size() - 1; i >= 0; i--) {
            var errors = transactions.getEnvelopes().get(i).getErrors();
            if (!errors.isEmpty()) {
                return errors.getLast().getResponse();
            }
        }
        return transactions.getErrors().isEmpty() ? "451 4.4.2 Delivery not confirmed"
                : transactions.getErrors().getLast().getResponse();
    }
}
