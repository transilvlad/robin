package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mimecast.robin.config.server.BotRetryConfig;
import com.mimecast.robin.config.server.RetryConfig;
import com.mimecast.robin.queue.QueueDiskSpaceGuard;
import com.mimecast.robin.queue.RetryScheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Keeps report bot endpoint requests that failed and sends them again later.
 * <p>Each request is stored as one JSON file under {@code <storage path>/bots/retry}, so it survives an
 * endpoint outage and a Robin restart. A single scheduled thread sends due requests, reschedules
 * failures using {@link RetryScheduler} with the {@link BotRetryConfig} backoff, and moves requests that
 * run out of retries, exceed the age limit, or are refused by the endpoint to {@code bots/dead}.
 * <p>The endpoints receiving these reports are expected to deduplicate them, since a request that timed
 * out may have been stored before it is sent again.
 * <p>Only one thread processes the folder. Bot threads add new files concurrently, which is safe because
 * each request has its own file and files are written atomically.
 */
public final class BotRetrySpool {
    private static final Logger log = LogManager.getLogger(BotRetrySpool.class);
    private static final Gson GSON = new Gson();
    private static final String SUFFIX = ".json";
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static volatile BotRetrySpool instance;

    private final BotRetryConfig config;
    private final RetryConfig retryConfig;
    private final Path retryDir;
    private final Path deadDir;
    private final LongSupplier clock;
    private final DiskCheck diskCheck;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger dead = new AtomicInteger();
    private ScheduledExecutorService scheduler;

    /**
     * Checks that there is room to write a request.
     */
    @FunctionalInterface
    interface DiskCheck {
        void check() throws IOException;
    }

    BotRetrySpool(BotRetryConfig config, Path baseDir, LongSupplier clock, DiskCheck diskCheck) throws IOException {
        this.config = config;
        this.retryConfig = config.getRetryConfig();
        this.retryDir = baseDir.resolve("retry");
        this.deadDir = baseDir.resolve("dead");
        this.clock = clock;
        this.diskCheck = diskCheck;
        createPrivateDirectories(retryDir);
        createPrivateDirectories(deadDir);
        pending.set(listFiles(retryDir).size());
        dead.set(listFiles(deadDir).size());
    }

    /**
     * Starts the retry service when enabled.
     *
     * @param config  Bot retry configuration.
     * @param baseDir Folder holding the {@code retry} and {@code dead} folders.
     */
    public static synchronized void start(BotRetryConfig config, Path baseDir) {
        if (instance != null) {
            return;
        }
        if (!config.isEnabled()) {
            log.info("Bot endpoint retries are disabled");
            return;
        }
        try {
            BotRetrySpool spool = new BotRetrySpool(config, baseDir,
                    () -> System.currentTimeMillis() / 1000L,
                    QueueDiskSpaceGuard::requireWritableStorageAndQueueSpace);
            spool.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "bot-retry");
                thread.setDaemon(true);
                return thread;
            });
            spool.scheduler.scheduleWithFixedDelay(spool::processDueSafely,
                    Math.min(5L, config.getPollSeconds()), config.getPollSeconds(), TimeUnit.SECONDS);
            instance = spool;
            log.info("Bot endpoint retries started in {} with {} pending", spool.retryDir, spool.pending.get());
        } catch (IOException e) {
            log.error("Unable to start bot endpoint retries in {}: {}", baseDir, e.getMessage());
        }
    }

    /**
     * Stops the retry service. Pending requests stay on disk for the next start.
     */
    public static synchronized void stop() {
        BotRetrySpool spool = instance;
        instance = null;
        if (spool != null && spool.scheduler != null) {
            spool.scheduler.shutdown();
            try {
                if (!spool.scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    spool.scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                spool.scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gets the running retry service.
     *
     * @return Instance or null when retries are not running.
     */
    static BotRetrySpool getInstance() {
        return instance;
    }

    /**
     * Sets the running retry service. For tests.
     *
     * @param spool Instance or null.
     */
    static void setInstance(BotRetrySpool spool) {
        instance = spool;
    }

    /**
     * Gets retry statistics for the health endpoint.
     *
     * @return JSON object string.
     */
    public static String getStatsJson() {
        BotRetrySpool spool = instance;
        if (spool == null) {
            return "{\"enabled\":false}";
        }
        Long oldest = spool.oldestFirstFailedAt();
        return String.format("{\"enabled\":true,\"pending\":%d,\"dead\":%d,\"oldestPendingEpochSeconds\":%s}",
                spool.pending.get(), spool.dead.get(), oldest != null ? oldest.toString() : "null");
    }

    /**
     * Stores a failed request for a later attempt.
     *
     * @param botName  Bot name.
     * @param endpoint Endpoint URL.
     * @param insecure Whether TLS verification is skipped.
     * @param headers  Request headers as sent.
     * @param payload  JSON payload.
     * @param error    Failure description.
     * @return True if stored, false if the limit is reached or the request could not be written.
     */
    boolean enqueue(String botName, String endpoint, boolean insecure, Map<String, String> headers,
                    String payload, String error) {
        if (pending.get() >= config.getMaxPending()) {
            log.error("Bot retry limit of {} pending reports reached, dropping {} report for {}",
                    config.getMaxPending(), botName, endpoint);
            return false;
        }
        long now = clock.getAsLong();
        int delay = RetryScheduler.getNextRetry(retryConfig, 1);
        if (delay < 0) {
            return false;
        }
        BotRetryEntry entry = new BotRetryEntry()
                .setUid(UUID.randomUUID().toString())
                .setBotName(botName)
                .setEndpoint(endpoint)
                .setInsecure(insecure)
                .setHeaders(headers)
                .setPayload(payload)
                .setRetryCount(1)
                .setFirstFailedAt(now)
                .setNextAttemptAt(now + delay)
                .setLastError(error);
        try {
            diskCheck.check();
            write(retryDir.resolve(fileName(entry)), entry);
            pending.incrementAndGet();
            return true;
        } catch (IOException e) {
            log.error("Unable to store {} report for retry: {}", botName, e.getMessage());
            return false;
        }
    }

    private void processDueSafely() {
        try {
            processDue();
        } catch (Exception e) {
            log.error("Bot retry run failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Sends every request that is due.
     *
     * @return Number of requests delivered.
     */
    int processDue() {
        int delivered = 0;
        for (Path file : listFiles(retryDir)) {
            BotRetryEntry entry = read(file);
            if (entry == null) {
                moveToDead(file, null, "unreadable retry file");
                continue;
            }
            long now = clock.getAsLong();
            if (entry.getNextAttemptAt() > now) {
                continue;
            }

            BotEndpointCaller.Outcome outcome = BotEndpointCaller.deliver(entry);
            switch (outcome.result()) {
                case DELIVERED -> {
                    delete(file);
                    delivered++;
                    log.info("Delivered {} report to {} on retry {}", entry.getBotName(), entry.getEndpoint(),
                            entry.getRetryCount());
                }
                case REJECTED -> moveToDead(file, entry, outcome.error());
                case DEFERRED -> {
                    // Circuit open: try again on a later run without using a retry.
                }
                case RETRY -> reschedule(file, entry, outcome.error(), now);
            }
        }
        return delivered;
    }

    private void reschedule(Path file, BotRetryEntry entry, String error, long now) {
        int retryCount = entry.getRetryCount() + 1;
        long maxAge = retryConfig.getMaxAgeSeconds();
        int delay = RetryScheduler.getNextRetry(retryConfig, retryCount);
        if (delay < 0 || (maxAge > 0 && now - entry.getFirstFailedAt() >= maxAge)) {
            moveToDead(file, entry, error);
            return;
        }
        entry.setRetryCount(retryCount).setNextAttemptAt(now + delay).setLastError(error);
        try {
            write(file, entry);
            log.debug("Retry {} of {} report to {} failed, next attempt in {}s: {}",
                    retryCount - 1, entry.getBotName(), entry.getEndpoint(), delay, error);
        } catch (IOException e) {
            log.error("Unable to reschedule {} report {}: {}", entry.getBotName(), entry.getUid(), e.getMessage());
        }
    }

    private void moveToDead(Path file, BotRetryEntry entry, String error) {
        String botName = entry != null ? entry.getBotName() : "unknown";
        String endpoint = entry != null ? entry.getEndpoint() : "unknown";
        log.error("Giving up on {} report to {} ({}): {}", botName, endpoint, file.getFileName(), error);
        try {
            if (entry != null) {
                entry.setLastError(error);
                write(file, entry);
            }
            move(file, deadDir.resolve(file.getFileName()));
            pending.decrementAndGet();
            dead.incrementAndGet();
        } catch (IOException e) {
            log.error("Unable to move {} to {}: {}", file, deadDir, e.getMessage());
        }
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
            pending.decrementAndGet();
        } catch (IOException e) {
            log.error("Unable to delete delivered retry file {}: {}", file, e.getMessage());
        }
    }

    private Long oldestFirstFailedAt() {
        if (pending.get() <= 0) {
            return null;
        }
        // File names start with the zero-padded first failure time, so the first name is the oldest.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(retryDir, "*" + SUFFIX)) {
            Long oldest = null;
            for (Path file : stream) {
                String name = file.getFileName().toString();
                int dash = name.indexOf('-');
                if (dash > 0) {
                    try {
                        long value = Long.parseLong(name.substring(0, dash));
                        oldest = oldest == null ? value : Math.min(oldest, value);
                    } catch (NumberFormatException ignored) {
                        // Not a retry file name.
                    }
                }
            }
            return oldest;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds a file name that sorts by first failure time.
     *
     * @param entry Retry entry.
     * @return File name.
     */
    static String fileName(BotRetryEntry entry) {
        return String.format("%012d-%s%s", entry.getFirstFailedAt(), entry.getUid(), SUFFIX);
    }

    /**
     * Gets the number of requests waiting.
     *
     * @return Pending count.
     */
    int getPending() {
        return pending.get();
    }

    /**
     * Gets the number of requests given up.
     *
     * @return Dead count.
     */
    int getDead() {
        return dead.get();
    }

    private static List<Path> listFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException e) {
            log.error("Unable to list {}: {}", dir, e.getMessage());
        }
        files.sort(null);
        return files;
    }

    private static BotRetryEntry read(Path file) {
        try {
            BotRetryEntry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BotRetryEntry.class);
            if (entry == null || entry.getEndpoint() == null || entry.getPayload() == null) {
                return null;
            }
            return entry;
        } catch (IOException | JsonParseException e) {
            log.warn("Unable to read retry file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static void write(Path target, BotRetryEntry entry) throws IOException {
        Path tmp = POSIX
                ? Files.createTempFile(target.getParent(), ".bot-retry-", ".tmp", privateFile())
                : Files.createTempFile(target.getParent(), ".bot-retry-", ".tmp");
        try {
            Files.writeString(tmp, GSON.toJson(entry), StandardCharsets.UTF_8);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createPrivateDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (POSIX) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static FileAttribute<?> privateFile() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }
}
