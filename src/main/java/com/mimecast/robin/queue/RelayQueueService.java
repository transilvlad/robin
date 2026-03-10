package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final long DISPATCH_IDLE_MIN_MILLIS = Config.getServer().getQueue().getLongProperty("dispatchIdleMinMillis", 5L);
    private static final long DISPATCH_IDLE_MAX_MILLIS = Config.getServer().getQueue().getLongProperty("dispatchIdleMaxMillis", 250L);
    private static final int OUTCOME_BATCH_SIZE = Math.toIntExact(
            Config.getServer().getQueue().getLongProperty("outcomeBatchSize", (long) Math.max(1, WORKER_THREADS))
    );
    private static final long OUTCOME_FLUSH_MILLIS = Config.getServer().getQueue().getLongProperty("outcomeFlushMillis", 5L);

    private static volatile ScheduledExecutorService scheduler;
    private static volatile ExecutorService dispatcherExecutor;
    private static volatile ThreadPoolExecutor workerExecutor;
    private static volatile ExecutorCompletionService<RelayQueueWorkResult> completionService;
    private static volatile PersistentQueue<RelaySession> queue;
    private static final String CONSUMER_ID = "robin-" + UUID.randomUUID();
    private static volatile boolean running;

    private static volatile long lastDispatchEpochSeconds = 0L;
    private static volatile long lastHousekeepingEpochSeconds = 0L;
    private static volatile long nextHousekeepingEpochSeconds = 0L;
    private static volatile long currentDispatchIdleMillis = DISPATCH_IDLE_MIN_MILLIS;
    private static volatile int currentOutcomeQueueDepth = 0;
    private static volatile int currentInFlight = 0;
    private static volatile int lastMutationBatchSize = 0;
    private static volatile long lastMutationCommitDurationMillis = 0L;

    public static synchronized void run() {
        if (scheduler != null) {
            return;
        }

        queue = PersistentQueue.getInstance();
        workerExecutor = new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, MAX_IN_FLIGHT)),
                new ThreadPoolExecutor.AbortPolicy()
        );
        completionService = new ExecutorCompletionService<>(workerExecutor);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        dispatcherExecutor = Executors.newSingleThreadExecutor();
        running = true;

        log.info("RelayQueueService starting: startDelaySeconds={}, housekeepingIntervalSeconds={}, initialQueueSize={}, maxClaimBatch={}, workerThreads={}, maxInFlight={}, outcomeBatchSize={}, outcomeFlushMillis={}",
                START_DELAY_SECONDS, HOUSEKEEPING_INTERVAL_SECONDS, queue.size(), MAX_CLAIM_BATCH, WORKER_THREADS, MAX_IN_FLIGHT,
                OUTCOME_BATCH_SIZE, OUTCOME_FLUSH_MILLIS);

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

        nextHousekeepingEpochSeconds = Instant.now().getEpochSecond() + START_DELAY_SECONDS;
        scheduler.scheduleAtFixedRate(housekeepingTask, START_DELAY_SECONDS, HOUSEKEEPING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        dispatcherExecutor.submit(() -> {
            sleepQuietly(TimeUnit.SECONDS.toMillis(START_DELAY_SECONDS));
            dispatchLoop();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(RelayQueueService::shutdown));
    }

    private static void dispatchLoop() {
        RelayDequeue dequeue = new RelayDequeue(queue);
        QueueMutationBatch.Builder<RelaySession> mutationBuilder = QueueMutationBatch.builder();
        List<Path> pendingCleanupPaths = new ArrayList<>();
        int runningTasks = 0;
        long oldestPendingOutcomeMillis = 0L;
        long idleBackoffMillis = DISPATCH_IDLE_MIN_MILLIS;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean progress = false;
                long nowMillis = System.currentTimeMillis();

                while (runningTasks > 0) {
                    Future<RelayQueueWorkResult> future = completionService.poll();
                    if (future == null) {
                        break;
                    }
                    RelayQueueWorkResult result = future.get();
                    runningTasks--;
                    progress |= collectResult(mutationBuilder, pendingCleanupPaths, result, nowMillis);
                    currentOutcomeQueueDepth = mutationBuilder.size();
                }

                if (shouldFlush(mutationBuilder, oldestPendingOutcomeMillis, runningTasks, nowMillis)) {
                    if (flushMutations(mutationBuilder, pendingCleanupPaths)) {
                        oldestPendingOutcomeMillis = 0L;
                        progress = true;
                    }
                } else if (!mutationBuilder.isEmpty() && oldestPendingOutcomeMillis == 0L) {
                    oldestPendingOutcomeMillis = nowMillis;
                }

                int inFlight = runningTasks + mutationBuilder.size();
                currentInFlight = inFlight;
                int claimBudget = calculateClaimBudget(MAX_CLAIM_BATCH, MAX_IN_FLIGHT, inFlight);
                if (claimBudget > 0) {
                    long now = Instant.now().getEpochSecond();
                    long claimUntil = now + CLAIM_TIMEOUT_SECONDS;
                    List<QueueItem<RelaySession>> claimedItems = queue.claimReady(claimBudget, now, CONSUMER_ID, claimUntil);
                    if (!claimedItems.isEmpty()) {
                        lastDispatchEpochSeconds = now;
                        for (QueueItem<RelaySession> item : claimedItems) {
                            completionService.submit(() -> dequeue.processClaimedItem(item, Instant.now().getEpochSecond()));
                            runningTasks++;
                        }
                        currentInFlight = runningTasks + mutationBuilder.size();
                        progress = true;
                    }
                }

                if (progress) {
                    idleBackoffMillis = DISPATCH_IDLE_MIN_MILLIS;
                    currentDispatchIdleMillis = idleBackoffMillis;
                    if (!mutationBuilder.isEmpty() && oldestPendingOutcomeMillis == 0L) {
                        oldestPendingOutcomeMillis = System.currentTimeMillis();
                    }
                    continue;
                }

                currentDispatchIdleMillis = idleBackoffMillis;
                sleepQuietly(idleBackoffMillis);
                idleBackoffMillis = Math.min(DISPATCH_IDLE_MAX_MILLIS, Math.max(DISPATCH_IDLE_MIN_MILLIS, idleBackoffMillis * 2));
            } catch (Exception e) {
                log.error("RelayQueueService dispatcher error: {}", e.getMessage(), e);
                sleepQuietly(currentDispatchIdleMillis);
            }
        }

        flushMutations(mutationBuilder, pendingCleanupPaths);
    }

    private static boolean collectResult(QueueMutationBatch.Builder<RelaySession> mutationBuilder,
                                         List<Path> pendingCleanupPaths,
                                         RelayQueueWorkResult result,
                                         long nowMillis) {
        if (result == null) {
            return false;
        }
        boolean changed = false;
        if (result.mutation() != null) {
            mutationBuilder.addMutation(result.mutation());
            changed = true;
        }
        if (!result.newItems().isEmpty()) {
            mutationBuilder.addNewItems(result.newItems());
            changed = true;
        }
        if (!result.cleanupPaths().isEmpty()) {
            pendingCleanupPaths.addAll(result.cleanupPaths());
            changed = true;
        }
        if (changed && mutationBuilder.size() == 1) {
            currentOutcomeQueueDepth = 1;
        }
        return changed;
    }

    private static boolean shouldFlush(QueueMutationBatch.Builder<RelaySession> mutationBuilder,
                                       long oldestPendingOutcomeMillis,
                                       int runningTasks,
                                       long nowMillis) {
        if (mutationBuilder.isEmpty()) {
            return false;
        }
        if (mutationBuilder.size() >= OUTCOME_BATCH_SIZE) {
            return true;
        }
        if (runningTasks == 0) {
            return true;
        }
        return oldestPendingOutcomeMillis > 0L && nowMillis - oldestPendingOutcomeMillis >= OUTCOME_FLUSH_MILLIS;
    }

    private static boolean flushMutations(QueueMutationBatch.Builder<RelaySession> mutationBuilder, List<Path> pendingCleanupPaths) {
        if (mutationBuilder.isEmpty() && pendingCleanupPaths.isEmpty()) {
            return false;
        }

        QueueMutationBatch<RelaySession> batch = mutationBuilder.build();
        int cleanupCount = pendingCleanupPaths.size();
        long startedAt = System.nanoTime();
        try {
            if (!batch.isEmpty()) {
                queue.applyMutations(batch);
                lastMutationBatchSize = batch.mutations().size();
            } else {
                lastMutationBatchSize = 0;
            }
            deletePaths(pendingCleanupPaths);
            mutationBuilder.clear();
            pendingCleanupPaths.clear();
            currentOutcomeQueueDepth = 0;
            lastMutationCommitDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return batch.mutations().size() > 0 || !batch.newItems().isEmpty() || cleanupCount > 0;
        } catch (Exception e) {
            lastMutationCommitDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.error("RelayQueueService commit error: {}", e.getMessage(), e);
            return false;
        }
    }

    private static void deletePaths(List<Path> paths) {
        for (Path path : paths) {
            if (path == null || !Files.exists(path)) {
                continue;
            }
            try {
                Files.delete(path);
            } catch (Exception e) {
                log.error("Failed to delete queue envelope file {}: {}", path, e.getMessage());
            }
        }
    }

    public static synchronized void shutdown() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        if (dispatcherExecutor != null) {
            dispatcherExecutor.shutdownNow();
            dispatcherExecutor = null;
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
            workerExecutor = null;
        }
        completionService = null;
        if (queue != null) {
            queue.close();
            queue = null;
        }
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

    public static long getDispatchIdleMinMillis() {
        return DISPATCH_IDLE_MIN_MILLIS;
    }

    public static long getDispatchIdleMaxMillis() {
        return DISPATCH_IDLE_MAX_MILLIS;
    }

    public static long getCurrentDispatchIdleMillis() {
        return currentDispatchIdleMillis;
    }

    public static int getOutcomeBatchSize() {
        return OUTCOME_BATCH_SIZE;
    }

    public static long getOutcomeFlushMillis() {
        return OUTCOME_FLUSH_MILLIS;
    }

    public static int getCurrentOutcomeQueueDepth() {
        return currentOutcomeQueueDepth;
    }

    public static int getCurrentInFlight() {
        return currentInFlight;
    }

    public static int getLastMutationBatchSize() {
        return lastMutationBatchSize;
    }

    public static long getLastMutationCommitDurationMillis() {
        return lastMutationCommitDurationMillis;
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
