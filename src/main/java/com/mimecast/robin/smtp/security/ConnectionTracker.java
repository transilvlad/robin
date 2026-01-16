package com.mimecast.robin.smtp.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks connection state for DoS protection.
 *
 * <p>This utility maintains per-IP connection counts, connection rate history,
 * command counts, and other metrics used for rate limiting and abuse detection.
 *
 * <p>Thread-safe implementation using concurrent data structures.
 * Automatic cleanup of stale entries via scheduled maintenance task.
 */
public final class ConnectionTracker {
    private static final Logger log = LogManager.getLogger(ConnectionTracker.class);

    private static final ConcurrentMap<String, IpConnectionState> ipStates = new ConcurrentHashMap<>();
    private static final AtomicInteger totalConnections = new AtomicInteger(0);
    private static final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ConnectionTracker-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private static final int CLEANUP_INTERVAL_SECONDS = 60;
    private static final int STALE_ENTRY_AGE_SECONDS = 300;

    static {
        // Start periodic cleanup task.
        cleanupScheduler.scheduleAtFixedRate(
                ConnectionTracker::cleanup,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Private constructor for utility class.
     */
    private ConnectionTracker() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Records a new connection from the specified IP address.
     *
     * @param ipAddress The IP address establishing the connection.
     */
    public static void recordConnection(String ipAddress) {
        if (ipAddress == null) {
            log.warn("Attempted to record connection with null IP address");
            return;
        }
        IpConnectionState state = ipStates.computeIfAbsent(ipAddress, IpConnectionState::new);
        state.recordConnection();
        totalConnections.incrementAndGet();
        log.debug("Connection recorded for IP: {} (active: {}, total: {})",
                ipAddress, state.getActiveConnections(), totalConnections.get());
    }

    /**
     * Records connection closure from the specified IP address.
     *
     * @param ipAddress The IP address closing the connection.
     */
    public static void recordDisconnection(String ipAddress) {
        if (ipAddress == null) {
            log.warn("Attempted to record disconnection with null IP address");
            return;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        if (state != null) {
            state.recordDisconnection();
            int total = totalConnections.decrementAndGet();
            log.debug("Disconnection recorded for IP: {} (active: {}, total: {})",
                    ipAddress, state.getActiveConnections(), total);
        }
    }

    /**
     * Gets the current number of active connections for an IP address.
     *
     * @param ipAddress The IP address to check.
     * @return Number of active connections.
     */
    public static int getActiveConnections(String ipAddress) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getActiveConnections() : 0;
    }

    /**
     * Gets the total number of active connections across all IPs.
     *
     * @return Total active connections.
     */
    public static int getTotalActiveConnections() {
        return totalConnections.get();
    }

    /**
     * Gets the number of recent connections from an IP within the specified window.
     *
     * @param ipAddress     The IP address to check.
     * @param windowSeconds Time window in seconds.
     * @return Number of connections within window.
     */
    public static int getRecentConnectionCount(String ipAddress, int windowSeconds) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getRecentConnectionCount(windowSeconds) : 0;
    }

    /**
     * Records a command execution for the specified IP.
     *
     * @param ipAddress The IP address executing the command.
     */
    public static void recordCommand(String ipAddress) {
        if (ipAddress == null) {
            log.warn("Attempted to record command with null IP address");
            return;
        }
        IpConnectionState state = ipStates.computeIfAbsent(ipAddress, IpConnectionState::new);
        state.recordCommand();
    }

    /**
     * Gets the number of commands executed in the last minute for an IP.
     *
     * @param ipAddress The IP address to check.
     * @return Number of commands in last minute.
     */
    public static int getCommandsPerMinute(String ipAddress) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getCommandsPerMinute() : 0;
    }

    /**
     * Records bytes transferred for the specified IP.
     *
     * @param ipAddress The IP address.
     * @param bytes     Number of bytes transferred.
     */
    public static void recordBytesTransferred(String ipAddress, long bytes) {
        if (ipAddress == null) {
            log.warn("Attempted to record bytes transferred with null IP address");
            return;
        }
        IpConnectionState state = ipStates.computeIfAbsent(ipAddress, IpConnectionState::new);
        state.recordBytesTransferred(bytes);
    }

    /**
     * Cleans up stale entries older than the configured age.
     */
    private static void cleanup() {
        try {
            long now = Instant.now().getEpochSecond();
            List<String> staleIps = ipStates.entrySet().stream()
                    .filter(entry -> entry.getValue().isStale(now, STALE_ENTRY_AGE_SECONDS))
                    .map(ConcurrentMap.Entry::getKey)
                    .collect(Collectors.toList());

            for (String ip : staleIps) {
                ipStates.remove(ip);
                log.debug("Cleaned up stale entry for IP: {}", ip);
            }

            if (!staleIps.isEmpty()) {
                log.info("Cleaned up {} stale connection tracking entries", staleIps.size());
            }
        } catch (Exception e) {
            log.error("Error during connection tracker cleanup: {}", e.getMessage());
        }
    }

    /**
     * Clears all tracking data (for testing purposes).
     */
    public static void reset() {
        ipStates.clear();
        totalConnections.set(0);
        log.info("Connection tracker reset");
    }

    /**
     * Shuts down the cleanup scheduler.
     */
    public static void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Per-IP connection state tracking.
     */
    private static class IpConnectionState {
        private final String ipAddress;
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private final ConcurrentHashMap<Long, Integer> connectionHistory = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Integer> commandHistory = new ConcurrentHashMap<>();
        private final AtomicLong bytesTransferred = new AtomicLong(0);
        private volatile long lastActivityTime = Instant.now().getEpochSecond();

        IpConnectionState(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        void recordConnection() {
            activeConnections.incrementAndGet();
            long now = Instant.now().getEpochSecond();
            connectionHistory.merge(now, 1, Integer::sum);
            lastActivityTime = now;
        }

        void recordDisconnection() {
            int active = activeConnections.decrementAndGet();
            if (active < 0) {
                activeConnections.set(0);
                log.warn("Negative active connection count corrected for IP: {}", ipAddress);
            }
            lastActivityTime = Instant.now().getEpochSecond();
        }

        int getActiveConnections() {
            return activeConnections.get();
        }

        int getRecentConnectionCount(int windowSeconds) {
            long now = Instant.now().getEpochSecond();
            long cutoff = now - windowSeconds;

            // Clean old entries while counting.
            return connectionHistory.entrySet().stream()
                    .filter(entry -> {
                        if (entry.getKey() < cutoff) {
                            connectionHistory.remove(entry.getKey());
                            return false;
                        }
                        return true;
                    })
                    .mapToInt(ConcurrentMap.Entry::getValue)
                    .sum();
        }

        void recordCommand() {
            long now = Instant.now().getEpochSecond();
            commandHistory.merge(now, 1, Integer::sum);
            lastActivityTime = now;
        }

        int getCommandsPerMinute() {
            long now = Instant.now().getEpochSecond();
            long cutoff = now - 60;

            // Clean old entries while counting.
            return commandHistory.entrySet().stream()
                    .filter(entry -> {
                        if (entry.getKey() < cutoff) {
                            commandHistory.remove(entry.getKey());
                            return false;
                        }
                        return true;
                    })
                    .mapToInt(ConcurrentMap.Entry::getValue)
                    .sum();
        }

        void recordBytesTransferred(long bytes) {
            bytesTransferred.addAndGet(bytes);
            lastActivityTime = Instant.now().getEpochSecond();
        }

        boolean isStale(long now, int maxAgeSeconds) {
            return activeConnections.get() == 0 && (now - lastActivityTime) > maxAgeSeconds;
        }
    }
}

