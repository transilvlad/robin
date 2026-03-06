package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.AdaptiveRateConfig;
import com.mimecast.robin.config.server.ListenerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AdaptiveRateLimiter.
 */
@Isolated
class AdaptiveRateLimiterTest {

    @BeforeEach
    void setUp() {
        ConnectionTracker.reset();
    }

    @AfterEach
    void tearDown() {
        ConnectionTracker.reset();
    }

    private ListenerConfig baseConfig(int maxTotal, int maxPerIp, int maxPerWindow, int maxCommands) {
        Map<String, Object> map = new HashMap<>();
        map.put("maxTotalConnections", (long) maxTotal);
        map.put("maxConnectionsPerIp", (long) maxPerIp);
        map.put("maxConnectionsPerWindow", (long) maxPerWindow);
        map.put("maxCommandsPerMinute", (long) maxCommands);
        return new ListenerConfig(map);
    }

    private AdaptiveRateConfig adaptiveConfig(boolean enabled, double high, double low, double factor) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("highThreshold", high);
        map.put("lowThreshold", low);
        map.put("reductionFactor", factor);
        return new AdaptiveRateConfig(map);
    }

    @Test
    void testDisabledAdaptiveReturnsBase() {
        ListenerConfig base = baseConfig(100, 10, 30, 100);
        AdaptiveRateConfig adaptive = adaptiveConfig(false, 0.8, 0.3, 0.5);

        // Add 90 connections (90% load) - but adaptive is disabled.
        for (int i = 0; i < 90; i++) {
            ConnectionTracker.recordConnection("10.0.0." + (i % 254 + 1));
        }

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertSame(base, result, "Disabled adaptive should return original config");
    }

    @Test
    void testBelowThresholdReturnsBase() {
        ListenerConfig base = baseConfig(100, 10, 30, 100);
        AdaptiveRateConfig adaptive = adaptiveConfig(true, 0.8, 0.3, 0.5);

        // Add 50 connections (50% load) - below high threshold.
        for (int i = 0; i < 50; i++) {
            ConnectionTracker.recordConnection("10.0.0." + (i % 254 + 1));
        }

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertSame(base, result, "Load below threshold should return original config");
    }

    @Test
    void testAboveThresholdReducesLimits() {
        ListenerConfig base = baseConfig(100, 10, 30, 100);
        AdaptiveRateConfig adaptive = adaptiveConfig(true, 0.8, 0.3, 0.5);

        // Add 85 connections (85% load) - above high threshold.
        for (int i = 0; i < 85; i++) {
            ConnectionTracker.recordConnection("10.0.0." + (i % 254 + 1));
        }

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertNotSame(base, result, "Load above threshold should return new config");
        assertEquals(5, result.getMaxConnectionsPerIp(), "Per-IP limit should be halved");
        assertEquals(15, result.getMaxConnectionsPerWindow(), "Per-window limit should be halved");
        assertEquals(50, result.getMaxCommandsPerMinute(), "Commands-per-minute limit should be halved");
        // Global max should remain unchanged.
        assertEquals(100, result.getMaxTotalConnections(), "Total limit should not change");
    }

    @Test
    void testZeroMaxTotalSkipsAdaptive() {
        ListenerConfig base = baseConfig(0, 10, 30, 100);
        AdaptiveRateConfig adaptive = adaptiveConfig(true, 0.8, 0.3, 0.5);

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertSame(base, result, "Zero maxTotal should skip adaptive and return base");
    }

    @Test
    void testCustomReductionFactor() {
        ListenerConfig base = baseConfig(100, 20, 60, 200);
        AdaptiveRateConfig adaptive = adaptiveConfig(true, 0.5, 0.2, 0.25);

        // Add 60 connections (60% load) - above 0.5 threshold.
        for (int i = 0; i < 60; i++) {
            ConnectionTracker.recordConnection("10.0.0." + (i % 254 + 1));
        }

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertEquals(5, result.getMaxConnectionsPerIp(), "Per-IP limit should be 25% of original");
        assertEquals(15, result.getMaxConnectionsPerWindow(), "Per-window limit should be 25% of original");
        assertEquals(50, result.getMaxCommandsPerMinute(), "Commands limit should be 25% of original");
    }

    @Test
    void testMinimumLimitOfOne() {
        ListenerConfig base = baseConfig(100, 1, 1, 1);
        AdaptiveRateConfig adaptive = adaptiveConfig(true, 0.8, 0.3, 0.1);

        // Add 90 connections (90% load).
        for (int i = 0; i < 90; i++) {
            ConnectionTracker.recordConnection("10.0.0." + (i % 254 + 1));
        }

        ListenerConfig result = AdaptiveRateLimiter.applyAdaptiveLimits(base, adaptive);
        assertEquals(1, result.getMaxConnectionsPerIp(), "Minimum limit should be 1, not 0");
        assertEquals(1, result.getMaxConnectionsPerWindow(), "Minimum limit should be 1, not 0");
        assertEquals(1, result.getMaxCommandsPerMinute(), "Minimum limit should be 1, not 0");
    }
}
