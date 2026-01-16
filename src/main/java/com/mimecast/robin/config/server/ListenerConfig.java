package com.mimecast.robin.config.server;

import com.mimecast.robin.config.ConfigFoundation;

import java.util.Map;

/**
 * Listener configuration for SMTP ports.
 *
 * <p>This class provides type safe access to listener-specific configuration.
 * <p>Each SMTP port (smtp, secure, submission) can have its own configuration.
 */
public class ListenerConfig extends ConfigFoundation {

    /**
     * Constructs a new ListenerConfig instance.
     */
    public ListenerConfig() {
        super();
    }

    /**
     * Constructs a new ListenerConfig instance with configuration map.
     *
     * @param map Configuration map.
     */
    public ListenerConfig(Map<String, Object> map) {
        super();
        this.map = map;
    }

    /**
     * Gets backlog size.
     *
     * @return Backlog size.
     */
    public int getBacklog() {
        return Math.toIntExact(getLongProperty("backlog", 25L));
    }

    /**
     * Gets minimum pool size.
     *
     * @return Thread pool min size.
     */
    public int getMinimumPoolSize() {
        return Math.toIntExact(getLongProperty("minimumPoolSize", 1L));
    }

    /**
     * Gets maximum pool size.
     *
     * @return Thread pool max size.
     */
    public int getMaximumPoolSize() {
        return Math.toIntExact(getLongProperty("maximumPoolSize", 10L));
    }

    /**
     * Gets thread keep alive time.
     *
     * @return Time in seconds.
     */
    public int getThreadKeepAliveTime() {
        return Math.toIntExact(getLongProperty("threadKeepAliveTime", 60L));
    }

    /**
     * Gets transactions limit.
     * <p>This defines how many commands will be processed before breaking receipt loop.
     *
     * @return Transactions limit.
     */
    public int getTransactionsLimit() {
        return Math.toIntExact(getLongProperty("transactionsLimit", 305L));
    }

    /**
     * Gets recipients limit.
     * <p>This defines how many recipients will be processed before rejecting them.
     *
     * @return Recipients limit.
     */
    public int getRecipientsLimit() {
        return Math.toIntExact(getLongProperty("recipientsLimit", 100L));
    }

    /**
     * Gets envelope limit.
     * <p>This defines how many envelopes will be processed before breaking receipt loop.
     *
     * @return Envelope limit.
     */
    public int getEnvelopeLimit() {
        return Math.toIntExact(getLongProperty("envelopeLimit", 100L));
    }

    /**
     * Gets email size limit.
     * <p>This defines how big emails will be accepted.
     *
     * @return Email size limit.
     */
    public int getEmailSizeLimit() {
        return Math.toIntExact(getLongProperty("emailSizeLimit", 10242400L)); // 10 MB.
    }

    /**
     * Gets error limit.
     * <p>This defines how many syntax errors should be permitted before interrupting the receipt.
     *
     * @return Error limit.
     */
    public int getErrorLimit() {
        return Math.toIntExact(getLongProperty("errorLimit", 3L));
    }

    /**
     * Gets maximum concurrent connections per IP address.
     * <p>This limits how many simultaneous connections a single IP can establish.
     *
     * @return Maximum connections per IP (default: 10, 0 to disable).
     */
    public int getMaxConnectionsPerIp() {
        return Math.toIntExact(getLongProperty("maxConnectionsPerIp", 10L));
    }

    /**
     * Gets maximum total concurrent connections.
     * <p>This limits the total number of simultaneous connections to the listener.
     *
     * @return Maximum total connections (default: 100, 0 to disable).
     */
    public int getMaxTotalConnections() {
        return Math.toIntExact(getLongProperty("maxTotalConnections", 100L));
    }

    /**
     * Gets connection rate limit time window in seconds.
     * <p>This defines the time window for measuring connection rate.
     *
     * @return Rate limit window in seconds (default: 60).
     */
    public int getRateLimitWindowSeconds() {
        return Math.toIntExact(getLongProperty("rateLimitWindowSeconds", 60L));
    }

    /**
     * Gets maximum new connections per IP within rate limit window.
     * <p>This limits how many new connections an IP can establish within the time window.
     *
     * @return Maximum connections per window (default: 30, 0 to disable).
     */
    public int getMaxConnectionsPerWindow() {
        return Math.toIntExact(getLongProperty("maxConnectionsPerWindow", 30L));
    }

    /**
     * Gets maximum commands per minute per connection.
     * <p>This limits command rate to prevent command flooding attacks.
     *
     * @return Maximum commands per minute (default: 100, 0 to disable).
     */
    public int getMaxCommandsPerMinute() {
        return Math.toIntExact(getLongProperty("maxCommandsPerMinute", 100L));
    }

    /**
     * Gets minimum data transfer rate in bytes per second.
     * <p>This prevents slowloris attacks during DATA/BDAT commands.
     *
     * @return Minimum bytes per second (default: 10240, 0 to disable).
     */
    public int getMinDataRateBytesPerSecond() {
        return Math.toIntExact(getLongProperty("minDataRateBytesPerSecond", 10240L));
    }

    /**
     * Gets maximum data transfer timeout in seconds.
     * <p>This sets an absolute maximum time for DATA/BDAT commands.
     *
     * @return Maximum data timeout in seconds (default: 300).
     */
    public int getMaxDataTimeoutSeconds() {
        return Math.toIntExact(getLongProperty("maxDataTimeoutSeconds", 300L));
    }

    /**
     * Gets tarpit delay in milliseconds for suspicious behavior.
     * <p>Progressive delays are applied when abuse patterns are detected.
     *
     * @return Tarpit delay in milliseconds (default: 1000, 0 to disable).
     */
    public int getTarpitDelayMillis() {
        return Math.toIntExact(getLongProperty("tarpitDelayMillis", 1000L));
    }

    /**
     * Gets whether to enable DoS protections.
     * <p>When disabled, all rate limiting and connection controls are bypassed.
     *
     * @return True if DoS protections enabled (default: true).
     */
    public boolean isDosProtectionEnabled() {
        return getBooleanProperty("dosProtectionEnabled", true);
    }

    // Setter methods for DoS protection configuration

    public ListenerConfig setDosProtectionEnabled(boolean enabled) {
        map.put("dosProtectionEnabled", enabled);
        return this;
    }

    public ListenerConfig setMaxConnectionsPerIp(int max) {
        map.put("maxConnectionsPerIp", (long) max);
        return this;
    }

    public ListenerConfig setMaxTotalConnections(int max) {
        map.put("maxTotalConnections", (long) max);
        return this;
    }

    public ListenerConfig setRateLimitWindowSeconds(int seconds) {
        map.put("rateLimitWindowSeconds", (long) seconds);
        return this;
    }

    public ListenerConfig setMaxConnectionsPerWindow(int max) {
        map.put("maxConnectionsPerWindow", (long) max);
        return this;
    }

    public ListenerConfig setMaxCommandsPerMinute(int max) {
        map.put("maxCommandsPerMinute", (long) max);
        return this;
    }

    public ListenerConfig setMinDataRateBytesPerSecond(int bytesPerSecond) {
        map.put("minDataRateBytesPerSecond", (long) bytesPerSecond);
        return this;
    }

    public ListenerConfig setMaxDataTimeoutSeconds(int seconds) {
        map.put("maxDataTimeoutSeconds", (long) seconds);
        return this;
    }

    public ListenerConfig setTarpitDelayMillis(int millis) {
        map.put("tarpitDelayMillis", (long) millis);
        return this;
    }
}
