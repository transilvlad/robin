package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Queue scheduler that claims ready work and hands it to delivery workers.
 */
public class RelayQueueCron {
    private static final Logger log = LogManager.getLogger(RelayQueueCron.class);

    private static final int INITIAL_DELAY_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("queueInitialDelay", 10L)
    );
    private static final int PERIOD_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("queueInterval", 30L)
    );
    private static final int MAX_CLAIM_PER_TICK = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("maxDequeuePerTick", 10L)
    );
    private static final int WORKER_THREADS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("workerThreads", (long) Math.max(2, Math.min(8, MAX_CLAIM_PER_TICK)))
    );
    private static final int MAX_IN_FLIGHT = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("maxInFlight", (long) Math.max(WORKER_THREADS * 2, MAX_CLAIM_PER_TICK))
    );
    private static final int CLAIM_TIMEOUT_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("claimTimeoutSeconds", 300L)
    );

    private static volatile ScheduledExecutorService scheduler;
    private static volatile ThreadPoolExecutor workerExecutor;
    private static volatile PersistentQueue<RelaySession> queue;
    private static final String CONSUMER_ID = "robin-" + UUID.randomUUID();

    private static volatile long lastExecutionEpochSeconds = 0L;
    private static volatile long nextExecutionEpochSeconds = 0L;

    public static synchronized void run() {
        if (scheduler != null) {
            return;
        }

        queue = PersistentQueue.getInstance();
        workerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(WORKER_THREADS);
        scheduler = Executors.newScheduledThreadPool(1);

        log.info("RelayQueueCron starting: initialDelaySeconds={}, periodSeconds={}, initialQueueSize={}, maxClaimPerTick={}, workerThreads={}, maxInFlight={}",
                INITIAL_DELAY_SECONDS, PERIOD_SECONDS, queue.size(), MAX_CLAIM_PER_TICK, WORKER_THREADS, MAX_IN_FLIGHT);

        Runnable task = () -> {
            try {
                long now = Instant.now().getEpochSecond();
                lastExecutionEpochSeconds = now;
                nextExecutionEpochSeconds = now + PERIOD_SECONDS;

                int released = queue.releaseExpiredClaims(now);
                if (released > 0) {
                    log.info("Released {} expired queue claims", released);
                }

                int inFlight = workerExecutor.getActiveCount() + workerExecutor.getQueue().size();
                int claimBudget = Math.min(MAX_CLAIM_PER_TICK, Math.max(0, MAX_IN_FLIGHT - inFlight));
                if (claimBudget <= 0) {
                    return;
                }

                RelayDequeue dequeue = new RelayDequeue(queue);
                long claimUntil = now + CLAIM_TIMEOUT_SECONDS;
                for (QueueItem<RelaySession> item : queue.claimReady(claimBudget, now, CONSUMER_ID, claimUntil)) {
                    workerExecutor.submit(() -> dequeue.processClaimedItem(item, Instant.now().getEpochSecond()));
                }
            } catch (Exception e) {
                log.error("RelayQueueCron task error: {}", e.getMessage(), e);
            }
        };

        nextExecutionEpochSeconds = Instant.now().getEpochSecond() + INITIAL_DELAY_SECONDS;
        scheduler.scheduleAtFixedRate(task, INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (scheduler != null) {
                    scheduler.shutdown();
                }
                if (workerExecutor != null) {
                    workerExecutor.shutdown();
                }
            } finally {
                if (queue != null) {
                    queue.close();
                }
            }
        }));
    }

    public static long getQueueSize() {
        PersistentQueue<RelaySession> q = queue != null ? queue : PersistentQueue.getInstance();
        return q.size();
    }

    public static QueueStats getQueueStats() {
        PersistentQueue<RelaySession> q = queue != null ? queue : PersistentQueue.getInstance();
        return q.stats();
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

    public static int getWorkerThreads() {
        return WORKER_THREADS;
    }

    public static int getMaxInFlight() {
        return MAX_IN_FLIGHT;
    }
}
