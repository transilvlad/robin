package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RelayQueue queue cron job.
 */
public class RelayQueueCron {
    private static final Logger log = LogManager.getLogger(RelayQueueCron.class);

    // Queue file from config.
    public static final File QUEUE_FILE = new File(Config.getServer().getRelay().getStringProperty("queueFile", "/tmp/robinRelayQueue.db"));

    // Scheduler configuration (seconds).
    private static final int INITIAL_DELAY_SECONDS = Math.toIntExact(Config.getServer().getRelay().getLongProperty("queueInitialDelay", 10L));
    private static final int PERIOD_SECONDS = Math.toIntExact(Config.getServer().getRelay().getLongProperty("queueInterval", 30L));

    // Batch dequeue configuration (items per tick).
    private static final int MAX_DEQUEUE_PER_TICK = Math.toIntExact(Config.getServer().getRelay().getLongProperty("maxDequeuePerTick", 10L));

    // Shared state
    private static volatile ScheduledExecutorService scheduler;
    private static volatile PersistentQueue<RelaySession> queue;

    // Timing info (epoch seconds)
    private static volatile long lastExecutionEpochSeconds = 0L;
    private static volatile long nextExecutionEpochSeconds = 0L;

    /**
     * Main method to start the cron job.
     */
    public static synchronized void run() {
        if (scheduler != null) {
            return; // already running
        }

        queue = PersistentQueue.getInstance(QUEUE_FILE);
        long initialQueueSize = queue.size();
        log.info("RelayQueueCron starting: queueFile={}, initialDelaySeconds={}, periodSeconds={}, initialQueueSize={}, maxDequeuePerTick={}",
                QUEUE_FILE.getAbsolutePath(), INITIAL_DELAY_SECONDS, PERIOD_SECONDS, initialQueueSize, MAX_DEQUEUE_PER_TICK);

        scheduler = Executors.newScheduledThreadPool(1);

        Runnable task = () -> {
            try {
                long now = Instant.now().getEpochSecond();
                lastExecutionEpochSeconds = now;
                nextExecutionEpochSeconds = lastExecutionEpochSeconds + PERIOD_SECONDS;

                long sizeBefore = queue.size();
                int budget = (int) Math.min(MAX_DEQUEUE_PER_TICK, sizeBefore);
                log.debug("Cron tick: nowEpoch={}, queueSizeBefore={} nextTickInSec={} budget={} ", now, sizeBefore, PERIOD_SECONDS, budget);

                if (budget <= 0) {
                    log.trace("Cron tick: queue empty, nothing to process");
                    return;
                }

                int processed = 0;
                for (; processed < budget; processed++) {
                    RelaySession relaySession = queue.dequeue();
                    if (relaySession == null) {
                        log.debug("Cron tick: queue drained early after {} items", processed);
                        break;
                    }

                    // Not yet time to retry, re-enqueue and move on (won't be retried again this tick due to fixed budget).
                    int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
                    long lastRetryTime = relaySession.getLastRetryTime();
                    long nextAllowed = lastRetryTime + nextRetrySeconds;
                    if (now < nextAllowed) {
                        log.info("Re-enqueueing session (too early): sessionUID={}, retryCount={}, lastRetryTime={}, now={}, nextAllowed={}, backoffSec={}",
                                relaySession.getSession().getUID(), relaySession.getRetryCount(), lastRetryTime, now, nextAllowed, nextRetrySeconds);

                        // Persist files again (idempotent) before putting back on queue.
                        QueueFiles.persistEnvelopeFiles(relaySession);
                        queue.enqueue(relaySession);
                        continue;
                    }

                    // Clear transaction list and try again.
                    relaySession.getSession().getSessionTransactionList().clear();

                    int envelopesCount = relaySession.getSession().getEnvelopes() != null ? relaySession.getSession().getEnvelopes().size() : 0;
                    int recipientsCount = 0;
                    if (relaySession.getSession().getEnvelopes() != null) {
                        for (MessageEnvelope e : relaySession.getSession().getEnvelopes()) {
                            if (e != null && e.getRcpts() != null) recipientsCount += e.getRcpts().size();
                        }
                    }
                    log.info("Processing session: uid={}, protocol={}, retryCount={}, envelopes={}, recipients={}",
                            relaySession.getSession().getUID(), relaySession.getProtocol(), relaySession.getRetryCount(), envelopesCount, recipientsCount);

                    if (relaySession.getProtocol().equalsIgnoreCase("dovecot-lda")) {
                        new DovecotLdaDelivery(relaySession).send();
                    } else {
                        new EmailDelivery(relaySession.getSession()).send();
                    }

                    // Remove successful recipients from the envelopes and successful envelopes from the session.
                    List<EnvelopeTransactionList> envelopes = relaySession.getSession().getSessionTransactionList().getEnvelopes();
                    List<MessageEnvelope> successfulEnvelopes = new ArrayList<>();
                    for (int i = 0; i < envelopes.size(); i++) {
                        EnvelopeTransactionList transactions = envelopes.get(i);
                        MessageEnvelope envelope = relaySession.getSession().getEnvelopes().get(i);

                        if (transactions.getErrors().isEmpty()) {
                            successfulEnvelopes.add(envelope);
                        } else {
                            // Some recipients succeeded, some failed. Remove successful recipients.
                            if (transactions.getRecipients() != transactions.getFailedRecipients()) {
                                envelope.setRcpts(transactions.getFailedRecipients());
                            }
                        }
                    }

                    // Remove fully successful envelopes.
                    successfulEnvelopes.forEach(envelope -> {
                        // Delete envelope file if it exists.
                        Path path = Path.of(envelope.getFile());
                        if (Files.exists(path)) {
                            try {
                                Files.delete(path);
                                log.debug("Deleted envelope file: {}", envelope.getFile());
                            } catch (IOException e) {
                                log.error("Failed to delete envelope file: {}, error={}", envelope.getFile(), e.getMessage());
                            }
                        }
                    });
                    relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);
                    int removed = successfulEnvelopes.size();
                    int remaining = relaySession.getSession().getEnvelopes().size();
                    log.info("Session processed: uid={}, removedEnvelopes={}, remainingEnvelopes={}",
                            relaySession.getSession().getUID(), removed, remaining);

                    // If there are still envelopes to process, check retry count.
                    // If retry count < 30, bump and re-enqueue.
                    // If retry count >= 30, generate bounces for each recipient in each envelope.
                    if (!relaySession.getSession().getEnvelopes().isEmpty()) {
                        if (relaySession.getRetryCount() < 30) {
                            relaySession.bumpRetryCount();
                            log.info("Re-enqueueing for retry: uid={}, newRetryCount={}", relaySession.getSession().getUID(), relaySession.getRetryCount());
                            // Ensure files are persisted before re-enqueue.
                            QueueFiles.persistEnvelopeFiles(relaySession);
                            queue.enqueue(relaySession);
                        } else {
                            int bounceCount = 0;
                            List<MessageEnvelope> remainingEnvs = relaySession.getSession().getEnvelopes();
                            List<String> lastRecipients = remainingEnvs.isEmpty() ? List.of() : remainingEnvs.getLast().getRcpts();
                            for (String recipient : lastRecipients) {
                                // Generate bounce email.
                                BounceMessageGenerator bounce = new BounceMessageGenerator(relaySession, recipient);

                                // Build the session.
                                RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                                        .setProtocol("esmtp");

                                // Create the envelope.
                                MessageEnvelope envelope = new MessageEnvelope()
                                        .setMail("mailer-daemon@" + Config.getServer().getHostname())
                                        .setRcpt(recipient)
                                        .setBytes(bounce.getStream().toByteArray());
                                relaySessionBounce.getSession().addEnvelope(envelope);

                                // Persist (no-op for bytes-only) and enqueue.
                                QueueFiles.persistEnvelopeFiles(relaySessionBounce);
                                queue.enqueue(relaySessionBounce);
                                bounceCount++;
                            }
                            log.warn("Max retries reached: uid={}, generatedBounces={}", relaySession.getSession().getUID(), bounceCount);
                        }
                    } else {
                        log.info("Session fully delivered: uid={}", relaySession.getSession().getUID());
                    }
                }

            } catch (Exception e) {
                log.error("RelayQueueCron task error: {}", e.getMessage(), e);
            }
        };

        // Schedule the task to run every minute after a minute.
        nextExecutionEpochSeconds = Instant.now().getEpochSecond() + INITIAL_DELAY_SECONDS;
        scheduler.scheduleAtFixedRate(task, INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("RelayQueueCron scheduled: initialDelaySeconds={}, periodSeconds={}", INITIAL_DELAY_SECONDS, PERIOD_SECONDS);

        // Add shutdown hook to close resources.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("RelayQueueCron shutdown initiated");
                if (scheduler != null) {
                    scheduler.shutdown();
                    log.debug("Scheduler shutdown requested");
                }
            } finally {
                if (queue != null) {
                    queue.close();
                    log.debug("Queue closed: {}", QUEUE_FILE.getAbsolutePath());
                }
            }
        }));
    }

    // ===== Exposed helpers for health/metrics =====

    public static long getQueueSize() {
        PersistentQueue<RelaySession> q = queue != null ? queue : PersistentQueue.getInstance(QUEUE_FILE);
        return q.size();
    }

    /**
     * Build a histogram of retryCount -> number of items.
     */
    public static Map<Integer, Long> getRetryHistogram() {
        Map<Integer, Long> histogram = new HashMap<>();
        PersistentQueue<RelaySession> q = queue != null ? queue : PersistentQueue.getInstance(QUEUE_FILE);
        for (RelaySession s : q.snapshot()) {
            int retry = s.getRetryCount();
            histogram.put(retry, histogram.getOrDefault(retry, 0L) + 1L);
        }
        return histogram;
    }

    public static long getLastExecutionEpochSeconds() {
        return lastExecutionEpochSeconds;
    }

    public static long getNextExecutionEpochSeconds() {
        return nextExecutionEpochSeconds;
    }

    public static int getInitialDelaySeconds() {
        return INITIAL_DELAY_SECONDS;
    }

    public static int getPeriodSeconds() {
        return PERIOD_SECONDS;
    }
}
