package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.client.ClientRset;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.transaction.Transaction;
import com.mimecast.robin.util.Sleep;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared LMTP delivery helper that reuses the server connection pool.
 */
public class PooledLmtpDelivery {
    private static final Logger log = LogManager.getLogger(PooledLmtpDelivery.class);
    private static final AtomicLong TRANSACTION_FAILURE_COUNT = new AtomicLong(0);
    private static final AtomicLong CONNECTION_FAILURE_COUNT = new AtomicLong(0);
    private static final AtomicLong SUCCESS_COUNT = new AtomicLong(0);

    /**
     * Delivers all envelopes in the supplied session using the shared LMTP pool.
     *
     * @param sourceSession      Session containing one or more LMTP envelopes.
     * @param maxAttempts        Maximum number of delivery attempts.
     * @param retryDelaySeconds  Delay between attempts.
     * @return True when every envelope was delivered successfully.
     */
    public boolean deliver(Session sourceSession, long maxAttempts, int retryDelaySeconds) {
        if (sourceSession == null || sourceSession.getEnvelopes().isEmpty()) {
            return false;
        }

        LmtpConnectionPool pool = getPool();
        if (pool == null) {
            log.error("LMTP connection pool not initialized");
            return false;
        }

        long attempts = Math.max(1, maxAttempts);
        for (long attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1 && retryDelaySeconds > 0) {
                sleepBeforeRetry(retryDelaySeconds);
            }

            LmtpConnectionPool.PooledLmtpConnection pooled = borrowConnection(pool, sourceSession.getEnvelopes().getFirst());
            if (pooled == null) {
                log.warn("Failed to acquire LMTP connection from pool on attempt {}", attempt);
                continue;
            }

            try {
                DeliveryAttemptResult result = deliverAttempt(sourceSession, pooled);
                if (result.success()) {
                    SUCCESS_COUNT.incrementAndGet();
                    returnConnection(pool, pooled);
                    return true;
                }

                if (result.reusable()) {
                    returnConnection(pool, pooled);
                    TRANSACTION_FAILURE_COUNT.incrementAndGet();
                } else {
                    invalidateConnection(pool, pooled);
                    CONNECTION_FAILURE_COUNT.incrementAndGet();
                }
                logAttemptFailure(attempt, attempts, result);
            } catch (Exception e) {
                invalidateConnection(pool, pooled);
                CONNECTION_FAILURE_COUNT.incrementAndGet();
                logAttemptFailure(attempt, attempts,
                        DeliveryAttemptResult.connectionFailureResult("LMTP delivery threw exception: " + e.getMessage()));
            }
            boolean partiallyDelivered = sourceSession.getSessionTransactionList().getEnvelopes().stream()
                    .anyMatch(this::hasConfirmedDelivery);
            if (partiallyDelivered) return false;
        }

        return false;
    }

    protected DeliveryAttemptResult deliverAttempt(Session sourceSession,
                                                   LmtpConnectionPool.PooledLmtpConnection pooled) throws IOException {
        sourceSession.getSessionTransactionList().clear();

        Connection connection = pooled.getConnection();
        Session pooledSession = pooled.getSession();
        syncSessionMetadata(sourceSession, pooledSession);

        for (int i = 0; i < sourceSession.getEnvelopes().size(); i++) {
            if (i > 0 && Config.getProperties().getBooleanProperty("rsetBetweenEnvelopes", false)) {
                resetEnvelopeState(connection);
            }

            MessageEnvelope envelope = sourceSession.getEnvelopes().get(i);
            prepareEnvelope(pooledSession, envelope);
            try {
                processEnvelope(connection);
                pooled.recordDeliveredMessage();
            } finally {
                copyLatestEnvelopeTransactions(sourceSession, pooledSession);
            }
        }

        List<EnvelopeTransactionList> envelopes = sourceSession.getSessionTransactionList().getEnvelopes();
        if (envelopes.size() != sourceSession.getEnvelopes().size()) {
            return DeliveryAttemptResult.connectionFailureResult("Missing LMTP transaction state after delivery attempt");
        }

        if (hasUnansweredCommand(envelopes)) {
            return DeliveryAttemptResult.connectionFailureResult("LMTP server closed the connection");
        }

        String transactionFailure = summarizeTransactionFailures(envelopes);
        if (transactionFailure != null) {
            return DeliveryAttemptResult.transactionFailureResult(transactionFailure);
        }

        return DeliveryAttemptResult.successResult();
    }

    protected LmtpConnectionPool getPool() {
        return Server.getLmtpPool();
    }

    protected LmtpConnectionPool.PooledLmtpConnection borrowConnection(LmtpConnectionPool pool, MessageEnvelope envelope) {
        return pool.borrow(envelope);
    }

    protected void returnConnection(LmtpConnectionPool pool, LmtpConnectionPool.PooledLmtpConnection pooled) {
        pool.returnConnection(pooled);
    }

    protected void invalidateConnection(LmtpConnectionPool pool, LmtpConnectionPool.PooledLmtpConnection pooled) {
        pool.invalidate(pooled);
    }

    protected void sleepBeforeRetry(int retryDelaySeconds) {
        Sleep.nap(retryDelaySeconds);
    }

    protected void resetEnvelopeState(Connection connection) throws IOException {
        new ClientRset().process(connection);
    }

    protected void processEnvelope(Connection connection) throws IOException {
        new LmtpBehaviour().process(connection);
    }

    public static long getTransactionFailureCount() {
        return TRANSACTION_FAILURE_COUNT.get();
    }

    public static long getConnectionFailureCount() {
        return CONNECTION_FAILURE_COUNT.get();
    }

    public static long getSuccessCount() {
        return SUCCESS_COUNT.get();
    }

    private void syncSessionMetadata(Session sourceSession, Session pooledSession) {
        pooledSession.setUID(sourceSession.getUID());
        pooledSession.setDirection(sourceSession.getDirection());
        pooledSession.setRetry(sourceSession.getRetry());
    }

    private void prepareEnvelope(Session pooledSession, MessageEnvelope envelope) {
        pooledSession.getEnvelopes().clear();
        pooledSession.addEnvelope(envelope);
        pooledSession.getSessionTransactionList().clear();
    }

    private void copyLatestEnvelopeTransactions(Session sourceSession, Session pooledSession) {
        if (pooledSession.getSessionTransactionList().getEnvelopes().isEmpty()) {
            var unknown = new EnvelopeTransactionList();
            unknown.addTransaction("DATA", "DATA", "", true);
            sourceSession.getSessionTransactionList().addEnvelope(unknown);
            return;
        }

        EnvelopeTransactionList latest = pooledSession.getSessionTransactionList().getEnvelopes().getLast();
        var copied = latest.clone();
        boolean admitted = copied.getRcpt().stream()
                .anyMatch(reply -> !reply.isError() && reply.getResponseCode().startsWith("2"));
        boolean lastChunk = !copied.getBdat().isEmpty() && copied.getBdat().getLast().getPayload() != null
                && copied.getBdat().getLast().getPayload().toUpperCase(java.util.Locale.ROOT).contains(" LAST");
        if (copied.getTransactions().isEmpty() || admitted && copied.getData() == null && !lastChunk
                && copied.getBdat().stream().noneMatch(Transaction::isError)) {
            copied.addTransaction("DATA", "DATA", "", true);
        }
        sourceSession.getSessionTransactionList().addEnvelope(copied);
    }

    private boolean hasConfirmedDelivery(EnvelopeTransactionList envelope) {
        if (!envelope.getLmtp().isEmpty()) {
            return envelope.getLmtp().stream()
                    .anyMatch(reply -> !reply.isError() && reply.getResponseCode().startsWith("2"));
        }
        return envelope.isMessageCommitted();
    }

    /**
     * An empty reply means the server closed the connection, so it must not be reused.
     */
    private boolean hasUnansweredCommand(List<EnvelopeTransactionList> envelopes) {
        for (EnvelopeTransactionList envelope : envelopes) {
            for (Transaction transaction : envelope.getTransactions()) {
                if (transaction != null && transaction.isError()
                        && (transaction.getResponse() == null || transaction.getResponse().isBlank())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String summarizeTransactionFailures(List<EnvelopeTransactionList> envelopes) {
        List<String> failures = new ArrayList<>();
        for (EnvelopeTransactionList envelope : envelopes) {
            envelope.getErrors().forEach(error -> failures.add(error.getCommand() + " " + error.getResponse()));
        }

        return failures.isEmpty() ? null : String.join(" | ", failures);
    }

    private void logAttemptFailure(long attempt, long maxAttempts, DeliveryAttemptResult result) {
        String reason = result.reason();
        if (attempt < maxAttempts) {
            log.warn("Attempt {} of {} {}", attempt, maxAttempts, reason);
        } else {
            log.error("Attempt {} of {} {}", attempt, maxAttempts, reason);
        }
    }

    protected record DeliveryAttemptResult(boolean success, boolean reusable, String reason) {
        private static DeliveryAttemptResult successResult() {
            return new DeliveryAttemptResult(true, true, null);
        }

        private static DeliveryAttemptResult transactionFailureResult(String reason) {
            return new DeliveryAttemptResult(false, true, reason);
        }

        private static DeliveryAttemptResult connectionFailureResult(String reason) {
            return new DeliveryAttemptResult(false, false, reason);
        }
    }
}
