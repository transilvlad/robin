package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.AdaptiveRateConfig;
import com.mimecast.robin.config.server.ListenerConfig;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies adaptive rate limiting by reducing connection limits under high server load.
 * <p>When the ratio of active connections to the configured maximum exceeds the high-load
 * threshold, this class returns a modified {@link ListenerConfig} with per-IP connection
 * and rate-window limits multiplied by the configured reduction factor.
 * <p>This class is stateless and thread-safe.
 */
public class AdaptiveRateLimiter {
    private static final Logger log = LogManager.getLogger(AdaptiveRateLimiter.class);

    /**
     * Returns an effective {@link ListenerConfig} with limits adjusted for current server load.
     * <p>If adaptive rate limiting is disabled, or if the current load is below the high-load
     * threshold, the original {@code base} config is returned unchanged.
     *
     * @param base     The base listener configuration.
     * @param adaptive The adaptive rate configuration.
     * @return The effective listener config to use for connection limit checks.
     */
    public static ListenerConfig applyAdaptiveLimits(ListenerConfig base, AdaptiveRateConfig adaptive) {
        if (!adaptive.isEnabled()) {
            return base;
        }

        int maxTotal = base.getMaxTotalConnections();
        if (maxTotal <= 0) {
            return base;
        }

        double load = ConnectionTracker.getTotalActiveConnections() / (double) maxTotal;

        if (load >= adaptive.getHighThreshold()) {
            double factor = adaptive.getReductionFactor();
            log.info("High server load detected ({} active / {} max): reducing connection limits by factor {}",
                    ConnectionTracker.getTotalActiveConnections(), maxTotal, factor);
            SmtpMetrics.incrementAdaptiveRateLimitApplied();
            return buildReducedConfig(base, factor);
        }

        return base;
    }

    /**
     * Builds a new {@link ListenerConfig} with per-IP and per-window limits multiplied by the given factor.
     *
     * @param base   The original listener configuration.
     * @param factor The reduction factor to apply (e.g., 0.5 to halve the limits).
     * @return A new ListenerConfig with reduced limits.
     */
    private static ListenerConfig buildReducedConfig(ListenerConfig base, double factor) {
        Map<String, Object> newMap = new HashMap<>(base.getMap());

        int reducedPerIp = Math.max(1, (int) (base.getMaxConnectionsPerIp() * factor));
        int reducedPerWindow = Math.max(1, (int) (base.getMaxConnectionsPerWindow() * factor));
        int reducedCommandsPerMin = Math.max(1, (int) (base.getMaxCommandsPerMinute() * factor));

        newMap.put("maxConnectionsPerIp", (long) reducedPerIp);
        newMap.put("maxConnectionsPerWindow", (long) reducedPerWindow);
        newMap.put("maxCommandsPerMinute", (long) reducedCommandsPerMin);

        log.debug("Adaptive limits: maxConnectionsPerIp={}, maxConnectionsPerWindow={}, maxCommandsPerMinute={}",
                reducedPerIp, reducedPerWindow, reducedCommandsPerMin);

        return new ListenerConfig(newMap);
    }
}
