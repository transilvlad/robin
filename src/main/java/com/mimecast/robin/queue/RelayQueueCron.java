package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RelayQueue queue cron job.
 * <p>Dequeues RelaySession items from the persistent queue and attempts delivery.
 * <p>Implements retry logic with exponential backoff and maximum retry limits.
 * <p>Handles processing of email delivery operations based on protocol.
 * <p>Relay feature if enabled and SMTP submissions will enqueue RelaySession items for processing here.
 */
public class RelayQueueCron {
    private static final Logger log = LogManager.getLogger(RelayQueueCron.class);

    // Queue file from config.
    public static final File QUEUE_FILE = new File(Config.getServer().getQueue().getStringProperty("queueFile", "/tmp/robinRelayQueue.db"));

    // Scheduler configuration (seconds).
    private static final int INITIAL_DELAY_SECONDS = Math.toIntExact(Config.getServer().getQueue().getLongProperty("queueInitialDelay", 10L));
    private static final int PERIOD_SECONDS = Math.toIntExact(Config.getServer().getQueue().getLongProperty("queueInterval", 30L));

    // Batch dequeue configuration (items per tick).
    private static final int MAX_DEQUEUE_PER_TICK = Math.toIntExact(Config.getServer().getQueue().getLongProperty("maxDequeuePerTick", 10L));

    // Shared state.
    private static volatile ScheduledExecutorService scheduler;
    private static volatile PersistentQueue<RelaySession> queue;

    // Timing info (epoch seconds).
    private static volatile long lastExecutionEpochSeconds = 0L;
    private static volatile long nextExecutionEpochSeconds = 0L;

    /**
     * Main method to start the cron job.
     */
    public static synchronized void run() {
        if (scheduler != null) {
            return; // Already running.
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

                // Delegate to RelayDequeue for processing
                RelayDequeue dequeue = new RelayDequeue(queue);
                dequeue.processBatch(MAX_DEQUEUE_PER_TICK, now);

            } catch (Exception e) {
                log.error("RelayQueueCron task error: {}", e.getMessage());
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

    /**
     * Get current queue size.
     */
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

    /** Getters for timing info */
    public static long getLastExecutionEpochSeconds() {
        return lastExecutionEpochSeconds;
    }

    /** Get next scheduled execution time (epoch seconds). */
    public static long getNextExecutionEpochSeconds() {
        return nextExecutionEpochSeconds;
    }

    /** Getters for scheduler configuration */
    public static int getInitialDelaySeconds() {
        return INITIAL_DELAY_SECONDS;
    }

    /** Get period between executions (seconds). */
    public static int getPeriodSeconds() {
        return PERIOD_SECONDS;
    }
}
