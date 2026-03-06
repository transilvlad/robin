package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for adaptive rate limiting.
 * <p>Adaptive rate limiting automatically tightens per-IP and rate-window limits
 * when the server is under load, based on the ratio of active to maximum connections.
 * <p>This configuration lives inline inside {@code server.json5} under the {@code adaptiveRate} key.
 */
public class AdaptiveRateConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new AdaptiveRateConfig instance.
     *
     * @param map Configuration map.
     */
    public AdaptiveRateConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if adaptive rate limiting is enabled.
     *
     * @return true if adaptive rate limiting is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Gets the high-load threshold above which limits are tightened.
     * <p>Expressed as a fraction (0.0–1.0) of total active vs. maximum connections.
     *
     * @return High-load threshold (default: 0.8).
     */
    public double getHighThreshold() {
        Object value = map.get("highThreshold");
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.8;
    }

    /**
     * Gets the low-load threshold below which normal limits are restored.
     * <p>Expressed as a fraction (0.0–1.0) of total active vs. maximum connections.
     *
     * @return Low-load threshold (default: 0.3).
     */
    public double getLowThreshold() {
        Object value = map.get("lowThreshold");
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.3;
    }

    /**
     * Gets the reduction factor applied to connection limits under high load.
     * <p>A value of 0.5 halves the effective limits when the server is overloaded.
     *
     * @return Reduction factor (default: 0.5).
     */
    public double getReductionFactor() {
        Object value = map.get("reductionFactor");
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.5;
    }
}
