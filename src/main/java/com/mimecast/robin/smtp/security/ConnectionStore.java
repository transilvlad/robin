package com.mimecast.robin.smtp.security;

/**
 * Abstraction for connection state storage used by {@link ConnectionTracker}.
 *
 * <p>Implementations may store state locally (in-process) or remotely (e.g. Redis)
 * to support single-node and clustered deployments respectively.
 *
 * @see LocalConnectionStore
 * @see RedisConnectionStore
 */
public interface ConnectionStore {

    /**
     * Records a new connection from the specified IP address.
     *
     * @param ipAddress The IP address establishing the connection.
     */
    void recordConnection(String ipAddress);

    /**
     * Records connection closure from the specified IP address.
     *
     * @param ipAddress The IP address closing the connection.
     */
    void recordDisconnection(String ipAddress);

    /**
     * Gets the current number of active connections for an IP address.
     *
     * @param ipAddress The IP address to check.
     * @return Number of active connections.
     */
    int getActiveConnections(String ipAddress);

    /**
     * Gets the total number of active connections across all IPs.
     *
     * @return Total active connections.
     */
    int getTotalActiveConnections();

    /**
     * Gets the number of recent connections from an IP within the specified window.
     *
     * @param ipAddress     The IP address to check.
     * @param windowSeconds Time window in seconds.
     * @return Number of connections within window.
     */
    int getRecentConnectionCount(String ipAddress, int windowSeconds);

    /**
     * Records a command execution for the specified IP.
     *
     * @param ipAddress The IP address executing the command.
     */
    void recordCommand(String ipAddress);

    /**
     * Gets the number of commands executed in the last minute for an IP.
     *
     * @param ipAddress The IP address to check.
     * @return Number of commands in last minute.
     */
    int getCommandsPerMinute(String ipAddress);

    /**
     * Records bytes transferred for the specified IP.
     *
     * @param ipAddress The IP address.
     * @param bytes     Number of bytes transferred.
     */
    void recordBytesTransferred(String ipAddress, long bytes);

    /**
     * Clears all tracking data.
     */
    void reset();

    /**
     * Shuts down any background resources held by this store.
     */
    void shutdown();
}
