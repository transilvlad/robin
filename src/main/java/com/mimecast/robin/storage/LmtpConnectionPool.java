package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.client.ClientRset;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection pool for LMTP deliveries with actual connection reuse.
 * <p>
 * Unlike a semaphore-based rate limiter, this pool maintains actual TCP connections
 * to the LMTP server and reuses them across multiple email deliveries. This eliminates
 * the overhead of TCP connection establishment and LHLO handshake for each email.
 * <p>
 * Pool lifecycle:
 * <ul>
 *   <li>borrow() - get an idle connection or create a new one (up to maxSize)</li>
 *   <li>return() - reset the connection state (RSET) and return to idle queue</li>
 *   <li>invalidate() - close the connection without returning to pool</li>
 * </ul>
 */
public class LmtpConnectionPool {
    private static final Logger log = LogManager.getLogger(LmtpConnectionPool.class);

    private final BlockingQueue<PooledLmtpConnection> idleConnections;
    private final int maxSize;
    private final long borrowTimeoutSeconds;
    private final long maxIdleTimeMs;
    private final long maxLifetimeMs;
    private final AtomicInteger totalConnections;
    private final AtomicInteger borrowedCount;

    // Server configuration
    private final List<String> servers;
    private final int port;
    private final boolean tls;

    private volatile boolean closed = false;

    /**
     * Creates an LMTP connection pool.
     *
     * @param maxSize              Maximum number of connections in the pool.
     * @param borrowTimeoutSeconds Maximum time to wait when borrowing a connection.
     * @param idleTimeoutSeconds   Close idle connections after this many seconds.
     * @param maxLifetimeSeconds   Close connections after this many seconds regardless of activity.
     * @param servers              List of LMTP server addresses.
     * @param port                 LMTP server port.
     * @param tls                  Whether to use TLS.
     */
    public LmtpConnectionPool(int maxSize, long borrowTimeoutSeconds, long idleTimeoutSeconds,
                              long maxLifetimeSeconds, List<String> servers, int port, boolean tls) {
        this.maxSize = maxSize;
        this.borrowTimeoutSeconds = borrowTimeoutSeconds;
        this.servers = servers;
        this.port = port;
        this.tls = tls;
        this.idleConnections = new LinkedBlockingQueue<>(maxSize);
        this.totalConnections = new AtomicInteger(0);
        this.borrowedCount = new AtomicInteger(0);
        this.maxIdleTimeMs = idleTimeoutSeconds * 1000L;
        this.maxLifetimeMs = maxLifetimeSeconds * 1000L;

        log.info("LMTP connection pool initialized: maxSize={}, borrowTimeout={}s, idleTimeout={}s, maxLifetime={}s, servers={}, port={}",
                maxSize, borrowTimeoutSeconds, idleTimeoutSeconds, maxLifetimeSeconds, servers, port);
    }

    /**
     * Borrows a connection from the pool.
     * <p>
     * First tries to get an idle connection. If none available and under max size,
     * creates a new connection. If at max size, waits for an idle connection.
     *
     * @param envelope The envelope to deliver (used to set up fresh session).
     * @return A pooled connection ready for use, or null if timeout/failure.
     */
    public PooledLmtpConnection borrow(MessageEnvelope envelope) {
        if (closed) {
            log.warn("Pool is closed, cannot borrow connection");
            return null;
        }

        // First, try to get an idle connection
        PooledLmtpConnection pooled = tryGetIdleConnection();
        if (pooled != null) {
            prepareForReuse(pooled, envelope);
            borrowedCount.incrementAndGet();
            log.debug("Borrowed existing connection (idle={}, total={}, borrowed={})",
                    idleConnections.size(), totalConnections.get(), borrowedCount.get());
            return pooled;
        }

        // Try to create a new connection if under limit
        if (totalConnections.get() < maxSize) {
            pooled = createNewConnection(envelope);
            if (pooled != null) {
                borrowedCount.incrementAndGet();
                log.debug("Created new connection (idle={}, total={}, borrowed={})",
                        idleConnections.size(), totalConnections.get(), borrowedCount.get());
                return pooled;
            }
        }

        // Wait for an idle connection
        try {
            log.debug("Pool exhausted, waiting for idle connection (max={}, borrowed={})",
                    maxSize, borrowedCount.get());
            pooled = idleConnections.poll(borrowTimeoutSeconds, TimeUnit.SECONDS);
            if (pooled != null) {
                if (validateConnection(pooled)) {
                    prepareForReuse(pooled, envelope);
                    borrowedCount.incrementAndGet();
                    return pooled;
                } else {
                    closeConnection(pooled);
                    // Try again with a fresh connection
                    return createNewConnection(envelope);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for connection");
        }

        log.warn("Timeout waiting for LMTP connection after {}s", borrowTimeoutSeconds);
        return null;
    }

    /**
     * Returns a connection to the pool after successful use.
     * <p>
     * Sends RSET to clear the session state and returns to idle queue.
     *
     * @param pooled The connection to return.
     */
    public void returnConnection(PooledLmtpConnection pooled) {
        if (pooled == null) {
            return;
        }

        borrowedCount.decrementAndGet();

        if (closed || !pooled.isValid()) {
            closeConnection(pooled);
            return;
        }

        // Send RSET to clear state
        if (!resetConnection(pooled)) {
            closeConnection(pooled);
            return;
        }

        pooled.touch();

        // Try to return to idle queue
        if (!idleConnections.offer(pooled)) {
            log.debug("Idle queue full, closing connection");
            closeConnection(pooled);
            return;
        }

        log.debug("Returned connection to pool (idle={}, total={}, borrowed={})",
                idleConnections.size(), totalConnections.get(), borrowedCount.get());
    }

    /**
     * Invalidates a connection (used after delivery failure).
     * <p>
     * Closes the connection without returning it to the pool.
     *
     * @param pooled The connection to invalidate.
     */
    public void invalidate(PooledLmtpConnection pooled) {
        if (pooled == null) {
            return;
        }

        borrowedCount.decrementAndGet();
        pooled.invalidate();
        closeConnection(pooled);

        log.debug("Invalidated connection (idle={}, total={}, borrowed={})",
                idleConnections.size(), totalConnections.get(), borrowedCount.get());
    }

    /**
     * Tries to get a valid idle connection from the queue.
     */
    private PooledLmtpConnection tryGetIdleConnection() {
        PooledLmtpConnection pooled;
        while ((pooled = idleConnections.poll()) != null) {
            if (validateConnection(pooled)) {
                return pooled;
            }
            closeConnection(pooled);
        }
        return null;
    }

    /**
     * Validates that a pooled connection is still usable.
     */
    private boolean validateConnection(PooledLmtpConnection pooled) {
        if (!pooled.isValid() || !pooled.isConnected()) {
            return false;
        }

        // Check idle timeout
        if (pooled.getIdleTime() > maxIdleTimeMs) {
            log.debug("Connection exceeded idle timeout ({}ms)", pooled.getIdleTime());
            return false;
        }

        // Check max lifetime
        if (pooled.getAge() > maxLifetimeMs) {
            log.debug("Connection exceeded max lifetime ({}ms)", pooled.getAge());
            return false;
        }

        return true;
    }

    /**
     * Creates a new LMTP connection.
     */
    private PooledLmtpConnection createNewConnection(MessageEnvelope envelope) {
        String serverKey = servers.get(0) + ":" + port;

        try {
            // Create session for LMTP
            Session session = Factories.getSession()
                    .setMx(servers)
                    .setPort(port)
                    .setTls(tls)
                    .setLhlo(Config.getServer().getHostname());

            // Add envelope
            session.addEnvelope(envelope);

            // Create and connect
            Connection connection = new Connection(session);
            connection.connect();

            // Do LHLO handshake
            LmtpBehaviour behaviour = new LmtpBehaviour();
            if (!behaviour.lhlo(connection)) {
                log.error("LHLO handshake failed for new connection");
                connection.close();
                return null;
            }

            totalConnections.incrementAndGet();
            PooledLmtpConnection pooled = new PooledLmtpConnection(connection, serverKey);
            log.debug("Created new LMTP connection to {}", serverKey);
            return pooled;

        } catch (Exception e) {
            log.error("Failed to create LMTP connection to {}: {}", serverKey, e.getMessage());
            return null;
        }
    }

    /**
     * Prepares a borrowed connection for reuse with a new envelope.
     */
    private void prepareForReuse(PooledLmtpConnection pooled, MessageEnvelope envelope) {
        Session session = pooled.getSession();
        // Clear old envelopes and add new one
        session.getEnvelopes().clear();
        session.addEnvelope(envelope);
        // Clear old transaction list for clean logging
        session.getSessionTransactionList().getEnvelopes().clear();
        pooled.touch();
    }

    /**
     * Sends RSET to reset the connection state.
     */
    private boolean resetConnection(PooledLmtpConnection pooled) {
        try {
            ClientRset rset = new ClientRset();
            return rset.process(pooled.getConnection());
        } catch (IOException e) {
            log.debug("RSET failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Closes a pooled connection gracefully with QUIT and decrements counter.
     */
    private void closeConnection(PooledLmtpConnection pooled) {
        totalConnections.decrementAndGet();
        // Send QUIT before closing to be polite to the server
        if (pooled.isConnected()) {
            new LmtpBehaviour().quit(pooled.getConnection());
        }
        pooled.close();
    }

    /**
     * Closes all connections and shuts down the pool.
     */
    public void close() {
        closed = true;

        PooledLmtpConnection pooled;
        while ((pooled = idleConnections.poll()) != null) {
            pooled.close();
            totalConnections.decrementAndGet();
        }

        log.info("LMTP connection pool closed");
    }

    /**
     * Gets the number of currently idle connections.
     *
     * @return Idle connection count.
     */
    public int getIdleCount() {
        return idleConnections.size();
    }

    /**
     * Gets the total number of connections (idle + borrowed).
     *
     * @return Total connection count.
     */
    public int getTotalConnections() {
        return totalConnections.get();
    }

    /**
     * Gets the number of currently borrowed connections.
     *
     * @return Borrowed connection count.
     */
    public int getBorrowedCount() {
        return borrowedCount.get();
    }

    /**
     * Gets the configured pool size.
     *
     * @return Maximum number of connections.
     */
    public int getPoolSize() {
        return maxSize;
    }

    /**
     * Gets the current number of available permits (for backward compatibility).
     *
     * @return Number of available connections (maxSize - borrowed).
     */
    public int getAvailablePermits() {
        return maxSize - borrowedCount.get();
    }

    /**
     * Gets the current number of active connections (for backward compatibility).
     *
     * @return Number of borrowed connections.
     */
    public int getActiveConnections() {
        return borrowedCount.get();
    }

    /**
     * Wrapper for a pooled LMTP connection with lifecycle tracking.
     * <p>
     * Tracks connection age, idle time, and validity for pool management.
     */
    public static class PooledLmtpConnection {
        private final Connection connection;
        private final String serverKey;
        private final long createdAt;
        private long lastUsedAt;
        private boolean valid = true;

        /**
         * Creates a pooled connection wrapper.
         *
         * @param connection The underlying SMTP connection.
         * @param serverKey  Server identifier (host:port).
         */
        PooledLmtpConnection(Connection connection, String serverKey) {
            this.connection = connection;
            this.serverKey = serverKey;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = createdAt;
        }

        /**
         * Gets the underlying connection.
         *
         * @return The connection.
         */
        public Connection getConnection() {
            return connection;
        }

        /**
         * Gets the session from the connection.
         *
         * @return The session.
         */
        public Session getSession() {
            return connection.getSession();
        }

        /**
         * Gets the server key.
         *
         * @return Server identifier.
         */
        public String getServerKey() {
            return serverKey;
        }

        /**
         * Updates the last used timestamp.
         */
        void touch() {
            lastUsedAt = System.currentTimeMillis();
        }

        /**
         * Marks the connection as invalid.
         */
        void invalidate() {
            valid = false;
        }

        /**
         * Checks if the connection is still valid.
         *
         * @return True if valid.
         */
        boolean isValid() {
            return valid;
        }

        /**
         * Checks if the underlying socket is connected.
         *
         * @return True if connected.
         */
        boolean isConnected() {
            return connection.isConnected();
        }

        /**
         * Gets the time since last use.
         *
         * @return Idle time in milliseconds.
         */
        long getIdleTime() {
            return System.currentTimeMillis() - lastUsedAt;
        }

        /**
         * Gets the connection age.
         *
         * @return Age in milliseconds.
         */
        long getAge() {
            return System.currentTimeMillis() - createdAt;
        }

        /**
         * Closes the underlying connection.
         */
        void close() {
            connection.close();
        }
    }
}
