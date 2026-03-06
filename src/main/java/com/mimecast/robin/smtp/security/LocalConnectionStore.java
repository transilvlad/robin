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
 * In-process implementation of {@link ConnectionStore}.
 *
 * <p>Stores all connection state in {@link ConcurrentHashMap} instances within the JVM.
 * Suitable for single-node deployments. Automatically cleans up stale entries via a
 * scheduled maintenance task.
 */
public class LocalConnectionStore implements ConnectionStore {
    private static final Logger log = LogManager.getLogger(LocalConnectionStore.class);

    private final ConcurrentMap<String, IpConnectionState> ipStates = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ConnectionTracker-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private static final int CLEANUP_INTERVAL_SECONDS = 60;
    private static final int STALE_ENTRY_AGE_SECONDS = 300;

    /**
     * Constructs a new LocalConnectionStore and starts the periodic cleanup task.
     */
    public LocalConnectionStore() {
        cleanupScheduler.scheduleAtFixedRate(
                this::cleanup,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    public void recordConnection(String ipAddress) {
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

    @Override
    public void recordDisconnection(String ipAddress) {
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

    @Override
    public int getActiveConnections(String ipAddress) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getActiveConnections() : 0;
    }

    @Override
    public int getTotalActiveConnections() {
        return totalConnections.get();
    }

    @Override
    public int getRecentConnectionCount(String ipAddress, int windowSeconds) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getRecentConnectionCount(windowSeconds) : 0;
    }

    @Override
    public void recordCommand(String ipAddress) {
        if (ipAddress == null) {
            log.warn("Attempted to record command with null IP address");
            return;
        }
        IpConnectionState state = ipStates.computeIfAbsent(ipAddress, IpConnectionState::new);
        state.recordCommand();
    }

    @Override
    public int getCommandsPerMinute(String ipAddress) {
        if (ipAddress == null) {
            return 0;
        }
        IpConnectionState state = ipStates.get(ipAddress);
        return state != null ? state.getCommandsPerMinute() : 0;
    }

    @Override
    public void recordBytesTransferred(String ipAddress, long bytes) {
        if (ipAddress == null) {
            log.warn("Attempted to record bytes transferred with null IP address");
            return;
        }
        IpConnectionState state = ipStates.computeIfAbsent(ipAddress, IpConnectionState::new);
        state.recordBytesTransferred(bytes);
    }

    @Override
    public void reset() {
        ipStates.clear();
        totalConnections.set(0);
        log.info("Connection tracker reset");
    }

    @Override
    public void shutdown() {
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
     * Cleans up stale entries older than the configured age.
     */
    private void cleanup() {
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
