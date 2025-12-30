package com.mimecast.robin.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Connection pool for LMTP deliveries.
 * <p>
 * Limits the number of concurrent LMTP connections to prevent overwhelming Dovecot.
 * Uses a semaphore to control access, with configurable pool size and timeout.
 */
public class LmtpConnectionPool {
    private static final Logger log = LogManager.getLogger(LmtpConnectionPool.class);

    private final Semaphore semaphore;
    private final int poolSize;
    private final long timeoutSeconds;

    /**
     * Creates an LMTP connection pool with specified size and timeout.
     *
     * @param poolSize       Maximum number of concurrent LMTP connections.
     * @param timeoutSeconds Maximum time to wait for a connection permit (in seconds).
     */
    public LmtpConnectionPool(int poolSize, long timeoutSeconds) {
        this.poolSize = poolSize;
        this.timeoutSeconds = timeoutSeconds;
        this.semaphore = new Semaphore(poolSize, true); // Fair ordering
        log.info("Initialized LMTP connection pool: size={}, timeout={}s", poolSize, timeoutSeconds);
    }

    /**
     * Acquires a connection permit from the pool.
     * <p>
     * Blocks until a permit is available or timeout is reached.
     * Must be paired with {@link #release()} in a try-finally block.
     *
     * @return True if permit was acquired, false if timeout occurred.
     */
    public boolean acquire() {
        try {
            int available = semaphore.availablePermits();
            if (available == 0) {
                log.debug("LMTP connection pool exhausted, waiting for permit (pool size: {})", poolSize);
            }

            boolean acquired = semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);

            if (acquired) {
                log.trace("Acquired LMTP connection permit (available: {})", semaphore.availablePermits());
            } else {
                log.warn("Timeout waiting for LMTP connection permit after {}s", timeoutSeconds);
            }

            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for LMTP connection permit", e);
            return false;
        }
    }

    /**
     * Releases a connection permit back to the pool.
     * <p>
     * Should always be called in a finally block after {@link #acquire()}.
     */
    public void release() {
        semaphore.release();
        log.trace("Released LMTP connection permit (available: {})", semaphore.availablePermits());
    }

    /**
     * Gets the current number of available permits.
     *
     * @return Number of available connection permits.
     */
    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * Gets the configured pool size.
     *
     * @return Maximum number of concurrent connections.
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Gets the current number of active (acquired) connections.
     *
     * @return Number of connections currently in use.
     */
    public int getActiveConnections() {
        return poolSize - semaphore.availablePermits();
    }
}
