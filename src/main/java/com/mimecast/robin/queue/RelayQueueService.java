package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Queue runtime that continuously dispatches ready work and periodically releases expired claims.
 */
public class RelayQueueService {
    private static final Logger log = LogManager.getLogger(RelayQueueService.class);

    private static final int START_DELAY_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("startDelaySeconds", 10L)
    );
    private static final int HOUSEKEEPING_INTERVAL_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("housekeepingIntervalSeconds", 30L)
    );
    private static final int MAX_CLAIM_BATCH = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("maxClaimBatch", 10L)
    );
    private static final int WORKER_THREADS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("workerThreads", (long) Math.max(2, Math.min(8, MAX_CLAIM_BATCH)))
    );
    private static final int MAX_IN_FLIGHT = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("maxInFlight", (long) WORKER_THREADS)
    );
    private static final int CLAIM_TIMEOUT_SECONDS = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("claimTimeoutSeconds", 300L)
    );
    private static final long DISPATCH_IDLE_MILLIS = Config.getServer().getQueue().getLongProperty("dispatchIdleMillis", 100L);

    private static volatile ScheduledExecutorService scheduler;
    private static volatile ExecutorService dispatcherExecutor;
    private static volatile ThreadPoolExecutor workerExecutor;
    private static volatile PersistentQueue<RelaySession> queue;
    private static final String CONSUMER_ID = "robin-" + UUID.randomUUID();
    private static volatile boolean running;

    private static volatile long lastDispatchEpochSeconds = 0L;
    private static volatile long lastHousekeepingEpochSeconds = 0L;
    private static volatile long nextHousekeepingEpochSeconds = 0L;

    public static synchronized void run() {
        if (scheduler != null) {
            return;
        }

        queue = PersistentQueue.getInstance();
        workerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(WORKER_THREADS);
        scheduler = Executors.newScheduledThreadPool(1);
        dispatcherExecutor = Executors.newSingleThreadExecutor();
        running = true;

        log.info("RelayQueueService starting: startDelaySeconds={}, housekeepingIntervalSeconds={}, initialQueueSize={}, maxClaimBatch={}, workerThreads={}, maxInFlight={}",
                START_DELAY_SECONDS, HOUSEKEEPING_INTERVAL_SECONDS, queue.size(), MAX_CLAIM_BATCH, WORKER_THREADS, MAX_IN_FLIGHT);

        Runnable housekeepingTask = () -> {
            try {
                long now = Instant.now().getEpochSecond();
                lastHousekeepingEpochSeconds = now;
                nextHousekeepingEpochSeconds = now + HOUSEKEEPING_INTERVAL_SECONDS;

                int released = queue.releaseExpiredClaims(now);
                if (released > 0) {
                    log.info("Released {} expired queue claims", released);
                }
            } catch (Exception e) {
                log.error("RelayQueueService housekeeping error: {}", e.getMessage(), e);
            }
        };

        Runnable dispatcherTask = () -> {
            RelayDequeue dequeue = new RelayDequeue(queue);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    long now = Instant.now().getEpochSecond();
                    int inFlight = workerExecutor.getActiveCount() + workerExecutor.getQueue().size();
                    int claimBudget = calculateClaimBudget(MAX_CLAIM_BATCH,
                            Math.min(MAX_IN_FLIGHT, workerExecutor.getMaximumPoolSize()),
                            inFlight);
                    if (claimBudget <= 0) {
                        sleepQuietly(DISPATCH_IDLE_MILLIS);
                        continue;
                    }

                    long claimUntil = now + CLAIM_TIMEOUT_SECONDS;
                    List<QueueItem<RelaySession>> claimedItems = queue.claimReady(claimBudget, now, CONSUMER_ID, claimUntil);
                    if (claimedItems.isEmpty()) {
                        sleepQuietly(DISPATCH_IDLE_MILLIS);
                        continue;
                    }

                    lastDispatchEpochSeconds = now;
                    for (QueueItem<RelaySession> item : claimedItems) {
                        workerExecutor.submit(() -> dequeue.processClaimedItem(item, Instant.now().getEpochSecond()));
                    }
                } catch (Exception e) {
                    log.error("RelayQueueService dispatcher error: {}", e.getMessage(), e);
                    sleepQuietly(DISPATCH_IDLE_MILLIS);
                }
            }
        };

        nextHousekeepingEpochSeconds = Instant.now().getEpochSecond() + START_DELAY_SECONDS;
        scheduler.scheduleAtFixedRate(housekeepingTask, START_DELAY_SECONDS, HOUSEKEEPING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        dispatcherExecutor.submit(() -> {
            sleepQuietly(TimeUnit.SECONDS.toMillis(START_DELAY_SECONDS));
            dispatcherTask.run();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                running = false;
                if (scheduler != null) {
                    scheduler.shutdown();
                }
                if (dispatcherExecutor != null) {
                    dispatcherExecutor.shutdownNow();
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

    public static long getLastDispatchEpochSeconds() {
        return lastDispatchEpochSeconds;
    }

    public static long getLastHousekeepingEpochSeconds() {
        return lastHousekeepingEpochSeconds;
    }

    public static long getNextHousekeepingEpochSeconds() {
        return nextHousekeepingEpochSeconds;
    }

    public static int getStartDelaySeconds() {
        return START_DELAY_SECONDS;
    }

    public static int getHousekeepingIntervalSeconds() {
        return HOUSEKEEPING_INTERVAL_SECONDS;
    }

    public static int getWorkerThreads() {
        return WORKER_THREADS;
    }

    public static int getMaxInFlight() {
        return MAX_IN_FLIGHT;
    }

    static int calculateClaimBudget(int maxClaimPerTick, int maxRunnable, int inFlight) {
        return Math.min(maxClaimPerTick, Math.max(0, maxRunnable - inFlight));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
