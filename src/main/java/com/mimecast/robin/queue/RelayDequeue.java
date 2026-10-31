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

        long maximumAge = RetryScheduler.getMaxAgeSeconds();
        if (maximumAge > 0 && currentEpochSeconds - queueItem.getCreatedAtEpochSeconds() >= maximumAge) {
            return expireClaimedItem(queueItem);
        }
        relaySession.getSession().getSessionTransactionList().clear();
        logSessionInfo(relaySession);
        attemptDelivery(relaySession);

        List<RecipientFailure> permanent = permanentRecipients(relaySession);
        if (!permanent.isEmpty() && allRecipientsPermanent(relaySession, permanent)) {
            return deadSession(queueItem, relaySession, List.of(), deriveLastError(relaySession));
        }
        List<RelaySession> notifications = persistPermanentNotifications(relaySession, permanent);
        try {
            return finishDelivery(queueItem, relaySession, currentEpochSeconds, permanent, notifications);
        } catch (RuntimeException e) {
            for (RelaySession notification : notifications) {
                deleteEnvelopeFiles(collectCleanupPaths(notification.getSession().getEnvelopes()));
            }
            throw e;
        }
    }

    private RelayQueueWorkResult finishDelivery(QueueItem<RelaySession> queueItem, RelaySession relaySession,
                                               long currentEpochSeconds, List<RecipientFailure> permanent,
                                               List<RelaySession> notifications) {
        if (!permanent.isEmpty()) {
            Set<RecipientFailure> terminal = new LinkedHashSet<>(permanent);
            for (MessageEnvelope envelope : relaySession.getSession().getEnvelopes()) {
                if (envelope.getRcpts() != null) {
                    envelope.setRcpts(new ArrayList<>(envelope.getRcpts().stream()
                            .filter(recipient -> !terminal.contains(new RecipientFailure(envelope, recipient))).toList()));
                }
            }
        }
        RelayDeliveryResult result = processDeliveryResults(relaySession);
        List<Path> cleanupPaths = collectCleanupPaths(result.getSuccessfulEnvelopes());
        log.info("Session processed: uid={}, removedEnvelopes={}, remainingEnvelopes={}",
                relaySession.getSession().getUID(), result.getRemovedCount(), result.getRemainingCount());

        queueItem.setPayload(relaySession).setRetryCount(relaySession.getRetryCount());

        if (relaySession.getSession().getEnvelopes().isEmpty()) {
            return new RelayQueueWorkResult(QueueMutation.acknowledge(queueItem), notifications, cleanupPaths);
        }

        if (relaySession.getRetryCount() < relaySession.getMaxRetryCount()
                && relaySession.getRetryCount() < RetryScheduler.getTotalRetries()) {
            RelayQueueWorkResult retry = retrySession(queueItem, relaySession, currentEpochSeconds, cleanupPaths);
            notifications.addAll(retry.newItems());
            return new RelayQueueWorkResult(retry.mutation(), notifications, retry.cleanupPaths());
        }

        String lastError = deriveLastError(relaySession);
        RelayQueueWorkResult dead = deadSession(queueItem, relaySession, cleanupPaths, lastError);
        notifications.addAll(dead.newItems());
        return new RelayQueueWorkResult(dead.mutation(), notifications, dead.cleanupPaths());
    }

    private record RecipientFailure(MessageEnvelope envelope, String recipient) {}

    private boolean smtp(RelaySession relay) {
        return "esmtp".equalsIgnoreCase(relay.getProtocol()) || "smtp".equalsIgnoreCase(relay.getProtocol())
                || "mx".equalsIgnoreCase(relay.getProtocol());
    }

    private Set<String> confirmedRecipients(MessageEnvelope envelope, EnvelopeTransactionList transactions, boolean smtp) {
        Set<String> confirmed = new LinkedHashSet<>();
        if (smtp && !transactions.isMessageCommitted()) return confirmed;
        List<Transaction> replies = smtp ? transactions.getRcpt() : transactions.getLmtp();
        if (smtp && replies.isEmpty()) confirmed.addAll(envelope.getRcpts());
        for (Transaction reply : replies) {
            if (!reply.isError() && reply.getResponseCode().startsWith("2") && reply.getAddress() != null) {
                confirmed.add(reply.getAddress());
            }
        }
        return confirmed;
    }

    private List<RecipientFailure> permanentRecipients(RelaySession relay) {
        boolean smtp = smtp(relay);
        boolean lmtp = "lmtp".equalsIgnoreCase(relay.getProtocol());
        if (!smtp && !lmtp) return List.of();
        List<RecipientFailure> failures = new ArrayList<>();
        var session = relay.getSession();
        var globalErrors = session.getSessionTransactionList().getErrors();
        boolean globalPermanent = !globalErrors.isEmpty()
                && globalErrors.stream().allMatch(error -> error.getResponseCode().startsWith("5"));
        var transactions = session.getSessionTransactionList().getEnvelopes();
        for (int i = 0; i < session.getEnvelopes().size(); i++) {
            var envelope = session.getEnvelopes().get(i);
            if (envelope.getRcpts() == null) continue;
            Set<String> recipients = new LinkedHashSet<>(envelope.getRcpts());
            var tx = i < transactions.size() ? transactions.get(i) : new EnvelopeTransactionList();
            Set<String> terminal = new LinkedHashSet<>();
            Set<String> confirmed = confirmedRecipients(envelope, tx, smtp);
            List<Transaction> recipientReplies = new ArrayList<>(tx.getRcpt());
            if (lmtp) recipientReplies.addAll(tx.getLmtp());
            for (Transaction reply : recipientReplies) {
                if (reply.isError() && reply.getResponseCode().startsWith("5") && reply.getAddress() != null) {
                    terminal.add(reply.getAddress());
                }
            }
            boolean mailPermanent = tx.getMail() != null && tx.getMail().isError()
                    && tx.getMail().getResponseCode().startsWith("5");
            boolean contentPermanent = (smtp || tx.getLmtp().isEmpty()) && tx.getTransactions().stream()
                    .anyMatch(reply -> (reply.getCommand().equalsIgnoreCase("DATA")
                            || reply.getCommand().equalsIgnoreCase("BDAT")) && reply.isError()
                            && reply.getResponseCode().startsWith("5"));
            if (contentPermanent) {
                if (tx.getRcpt().isEmpty()) {
                    terminal.addAll(envelope.getRcpts());
                } else {
                    for (Transaction admission : tx.getRcpt()) {
                        if (!admission.isError() && admission.getResponseCode().startsWith("2")) {
                            terminal.add(admission.getAddress());
                        }
                    }
                }
            }
            boolean legacyPermanent = !tx.getErrors().isEmpty() && tx.getErrors().stream()
                    .allMatch(reply -> reply.getResponseCode().startsWith("5"))
                    && tx.getRcpt().stream().allMatch(reply -> reply.getAddress() == null)
                    && tx.getLmtp().stream().allMatch(reply -> reply.getAddress() == null);
            if (mailPermanent || globalPermanent || legacyPermanent) terminal.addAll(envelope.getRcpts());
            terminal.removeAll(confirmed);
            for (String recipient : terminal) {
                if (recipients.contains(recipient)) failures.add(new RecipientFailure(envelope, recipient));
            }
        }
        return failures;
    }

    private boolean allRecipientsPermanent(RelaySession relay, List<RecipientFailure> failures) {
        Set<RecipientFailure> terminal = new LinkedHashSet<>(failures);
        for (var envelope : relay.getSession().getEnvelopes()) {
            if (envelope.getRcpts() == null || envelope.getRcpts().isEmpty()) return false;
            for (String recipient : envelope.getRcpts()) {
                if (!terminal.contains(new RecipientFailure(envelope, recipient))) return false;
            }
        }
        return true;
    }

    private List<RelaySession> persistPermanentNotifications(RelaySession relay, List<RecipientFailure> failures) {
        List<RelaySession> notifications = new ArrayList<>();
        if (!Config.getServer().getRelay().getBooleanProperty("bounce", true)) return notifications;
        try {
            for (RecipientFailure failure : failures) {
                RelaySession notification = createBounceSession(relay, failure.envelope(), failure.recipient());
                if (notification != null) notifications.add(notification);
            }
            return notifications;
        } catch (RuntimeException e) {
            for (RelaySession notification : notifications) {
                deleteEnvelopeFiles(collectCleanupPaths(notification.getSession().getEnvelopes()));
            }
            throw e;
        }
    }

    RelayQueueWorkResult expireClaimedItem(QueueItem<RelaySession> item) {
        RelaySession session = item.getPayload();
        if (session == null || session.getSession() == null) {
            return new RelayQueueWorkResult(QueueMutation.acknowledge(item), List.of(), List.of());
        }
        session.getSession().getSessionTransactionList().clear();
        String error = "554 5.4.7 Delivery time expired";
        session.getSession().getSessionTransactionList().addTransaction("QUEUE", error, true);
        return deadSession(item, session, List.of(), error);
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
        List<EnvelopeTransactionList> successfulTransactions = new ArrayList<>();

        List<MessageEnvelope> envelopes = relaySession.getSession().getEnvelopes();
        if (transactions.size() != envelopes.size()) {
            log.error("Transaction/envelope size mismatch: txCount={}, envCount={}, uid={}",
                    transactions.size(), envelopes.size(), relaySession.getSession().getUID());
        }

        for (int i = 0; i < Math.min(transactions.size(), envelopes.size()); i++) {
            EnvelopeTransactionList txList = transactions.get(i);
            MessageEnvelope envelope = envelopes.get(i);

            boolean smtp = smtp(relaySession);
            boolean lmtp = "lmtp".equalsIgnoreCase(relaySession.getProtocol());
            Set<String> confirmed = confirmedRecipients(envelope, txList, smtp);
            if (envelope.getRcpts().isEmpty()) {
                successfulEnvelopes.add(envelope);
                successfulTransactions.add(txList);
            } else if (smtp && txList.isMessageCommitted()
                    || lmtp && !txList.getLmtp().isEmpty()) {
                envelope.setRcpts(new ArrayList<>(envelope.getRcpts().stream()
                        .filter(recipient -> !confirmed.contains(recipient)).toList()));
                if (envelope.getRcpts().isEmpty()) {
                    successfulEnvelopes.add(envelope);
                    successfulTransactions.add(txList);
                }
            } else if (!smtp && (!lmtp || txList.getRcpt().isEmpty())) {
                if (txList.getErrors().isEmpty()) {
                    successfulEnvelopes.add(envelope);
                    successfulTransactions.add(txList);
                } else {
                    List<String> failed = txList.getFailedRecipients().stream().filter(envelope.getRcpts()::contains).toList();
                    if (!failed.isEmpty()) envelope.setRcpts(new ArrayList<>(failed));
                }
            }
        }

        relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);
        transactions.removeAll(successfulTransactions);

        return new RelayDeliveryResult(
                successfulEnvelopes.size(),
                relaySession.getSession().getEnvelopes().size(),
                successfulEnvelopes
        );
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
        List<RecipientFailure> failures = new ArrayList<>();
        List<MessageEnvelope> remainingEnvelopes = relaySession.getSession().getEnvelopes();
        for (MessageEnvelope envelope : remainingEnvelopes) {
            if (envelope != null && envelope.getRcpts() != null) {
                for (String recipient : new LinkedHashSet<>(envelope.getRcpts())) {
                    failures.add(new RecipientFailure(envelope, recipient));
                }
            }
        }
        List<RelaySession> bounces = persistPermanentNotifications(relaySession, failures);

        log.warn("Max retries reached: uid={}, generatedBounces={}",
                relaySession.getSession().getUID(), bounces.size());
        return bounces;
    }

    RelaySession createBounceSession(RelaySession originalSession, String recipient) {
        return persistBounce(new BounceMessageGenerator(originalSession, recipient));
    }

    RelaySession createBounceSession(RelaySession originalSession, MessageEnvelope envelope, String recipient) {
        return persistBounce(new BounceMessageGenerator(originalSession, envelope, recipient));
    }

    private RelaySession persistBounce(BounceMessageGenerator bounce) {
        RelaySession bounceSession = bounce.toRelaySession();
        if (bounceSession == null) {
            return null;
        }
        if (!QueueFiles.persistEnvelopeFiles(bounceSession)) {
            deleteEnvelopeFiles(collectCleanupPaths(bounceSession.getSession().getEnvelopes()));
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
        var envelopes = relaySession.getSession().getEnvelopes();
        for (int i = envelopes.size() - 1; i >= 0; i--) {
            var envelope = envelopes.get(i);
            if (envelope.getRcpts() != null && !envelope.getRcpts().isEmpty()) {
                return relaySession.getRejection(envelope, envelope.getRcpts().getLast());
            }
        }
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
