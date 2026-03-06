package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for distributed rate limiting via Redis.
 *
 * <p>When enabled, {@link com.mimecast.robin.smtp.security.ConnectionTracker} delegates
 * to a Redis-backed store so that connection state is shared across cluster nodes.
 *
 * <p>Loaded from {@code distributed-rate.json5}.
 */
public class DistributedRateConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new DistributedRateConfig instance.
     *
     * @param map Configuration map, or {@code null} for defaults.
     */
    public DistributedRateConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Whether distributed rate limiting is enabled.
     *
     * @return {@code true} if enabled.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Redis host.
     *
     * @return Host string (default: {@code localhost}).
     */
    public String getHost() {
        Object value = map.get("host");
        return value instanceof String ? (String) value : "localhost";
    }

    /**
     * Redis port.
     *
     * @return Port number (default: {@code 6379}).
     */
    public int getPort() {
        Object value = map.get("port");
        return value instanceof Number ? ((Number) value).intValue() : 6379;
    }

    /**
     * Redis password.
     *
     * @return Password string, empty if not required.
     */
    public String getPassword() {
        Object value = map.get("password");
        return value instanceof String ? (String) value : "";
    }

    /**
     * Key prefix for all Redis keys written by this store.
     *
     * @return Key prefix (default: {@code robin:conn:}).
     */
    public String getKeyPrefix() {
        Object value = map.get("keyPrefix");
        return value instanceof String ? (String) value : "robin:conn:";
    }

    /**
     * Maximum total connections in the Jedis connection pool.
     *
     * @return Pool size (default: {@code 10}).
     */
    public int getPoolMaxTotal() {
        Object value = map.get("poolMaxTotal");
        return value instanceof Number ? ((Number) value).intValue() : 10;
    }

    /**
     * TTL in seconds applied to Redis keys to prevent unbounded growth.
     *
     * @return TTL seconds (default: {@code 600}).
     */
    public int getKeyTtlSeconds() {
        Object value = map.get("keyTtlSeconds");
        return value instanceof Number ? ((Number) value).intValue() : 600;
    }
}
