package com.mimecast.robin.smtp.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tracks connection state for DoS protection.
 *
 * <p>Static facade that delegates all operations to a {@link ConnectionStore}.
 * The default store is a {@link LocalConnectionStore} (in-process).
 * For clustered deployments, inject a {@link RedisConnectionStore} via {@link #setStore(ConnectionStore)}
 * during application startup.
 *
 * <p>All callers remain unchanged — the store selection is transparent.
 *
 * @see LocalConnectionStore
 * @see RedisConnectionStore
 * @see ConnectionStoreFactory
 */
public final class ConnectionTracker {
    private static final Logger log = LogManager.getLogger(ConnectionTracker.class);

    private static volatile ConnectionStore store = new LocalConnectionStore();

    /**
     * Private constructor for utility class.
     */
    private ConnectionTracker() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Replaces the active {@link ConnectionStore}.
     * <p>Call this during application startup before any connections are accepted.
     *
     * @param s The store implementation to use.
     */
    public static void setStore(ConnectionStore s) {
        store = s;
    }

    /**
     * Records a new connection from the specified IP address.
     *
     * @param ipAddress The IP address establishing the connection.
     */
    public static void recordConnection(String ipAddress) {
        store.recordConnection(ipAddress);
    }

    /**
     * Records connection closure from the specified IP address.
     *
     * @param ipAddress The IP address closing the connection.
     */
    public static void recordDisconnection(String ipAddress) {
        store.recordDisconnection(ipAddress);
    }

    /**
     * Gets the current number of active connections for an IP address.
     *
     * @param ipAddress The IP address to check.
     * @return Number of active connections.
     */
    public static int getActiveConnections(String ipAddress) {
        return store.getActiveConnections(ipAddress);
    }

    /**
     * Gets the total number of active connections across all IPs.
     *
     * @return Total active connections.
     */
    public static int getTotalActiveConnections() {
        return store.getTotalActiveConnections();
    }

    /**
     * Gets the number of recent connections from an IP within the specified window.
     *
     * @param ipAddress     The IP address to check.
     * @param windowSeconds Time window in seconds.
     * @return Number of connections within window.
     */
    public static int getRecentConnectionCount(String ipAddress, int windowSeconds) {
        return store.getRecentConnectionCount(ipAddress, windowSeconds);
    }

    /**
     * Records a command execution for the specified IP.
     *
     * @param ipAddress The IP address executing the command.
     */
    public static void recordCommand(String ipAddress) {
        store.recordCommand(ipAddress);
    }

    /**
     * Gets the number of commands executed in the last minute for an IP.
     *
     * @param ipAddress The IP address to check.
     * @return Number of commands in last minute.
     */
    public static int getCommandsPerMinute(String ipAddress) {
        return store.getCommandsPerMinute(ipAddress);
    }

    /**
     * Records bytes transferred for the specified IP.
     *
     * @param ipAddress The IP address.
     * @param bytes     Number of bytes transferred.
     */
    public static void recordBytesTransferred(String ipAddress, long bytes) {
        store.recordBytesTransferred(ipAddress, bytes);
    }

    /**
     * Clears all tracking data (for testing purposes).
     */
    public static void reset() {
        store.reset();
    }

    /**
     * Shuts down the active store (releases background resources).
     */
    public static void shutdown() {
        store.shutdown();
    }
}
