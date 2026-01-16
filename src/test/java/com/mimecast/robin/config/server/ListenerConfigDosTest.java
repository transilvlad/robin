package com.mimecast.robin.config.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ListenerConfig DoS protection properties.
 */
class ListenerConfigDosTest {

    private ListenerConfig config;

    @BeforeEach
    void setUp() {
        config = new ListenerConfig();
    }

    @Test
    void testDefaultDosProtectionEnabled() {
        assertTrue(config.isDosProtectionEnabled(),
            "DoS protection should be enabled by default");
    }

    @Test
    void testSetDosProtectionEnabled() {
        config.setDosProtectionEnabled(false);
        assertFalse(config.isDosProtectionEnabled());

        config.setDosProtectionEnabled(true);
        assertTrue(config.isDosProtectionEnabled());
    }

    @Test
    void testDefaultMaxConnectionsPerIp() {
        assertEquals(10, config.getMaxConnectionsPerIp(),
            "Default maxConnectionsPerIp should be 10");
    }

    @Test
    void testSetMaxConnectionsPerIp() {
        config.setMaxConnectionsPerIp(25);
        assertEquals(25, config.getMaxConnectionsPerIp());

        config.setMaxConnectionsPerIp(0); // Disabled
        assertEquals(0, config.getMaxConnectionsPerIp());
    }

    @Test
    void testDefaultMaxTotalConnections() {
        assertEquals(100, config.getMaxTotalConnections(),
            "Default maxTotalConnections should be 100");
    }

    @Test
    void testSetMaxTotalConnections() {
        config.setMaxTotalConnections(500);
        assertEquals(500, config.getMaxTotalConnections());

        config.setMaxTotalConnections(0); // Disabled
        assertEquals(0, config.getMaxTotalConnections());
    }

    @Test
    void testDefaultRateLimitWindowSeconds() {
        assertEquals(60, config.getRateLimitWindowSeconds(),
            "Default rate limit window should be 60 seconds");
    }

    @Test
    void testSetRateLimitWindowSeconds() {
        config.setRateLimitWindowSeconds(120);
        assertEquals(120, config.getRateLimitWindowSeconds());

        config.setRateLimitWindowSeconds(30);
        assertEquals(30, config.getRateLimitWindowSeconds());
    }

    @Test
    void testDefaultMaxConnectionsPerWindow() {
        assertEquals(30, config.getMaxConnectionsPerWindow(),
            "Default maxConnectionsPerWindow should be 30");
    }

    @Test
    void testSetMaxConnectionsPerWindow() {
        config.setMaxConnectionsPerWindow(50);
        assertEquals(50, config.getMaxConnectionsPerWindow());

        config.setMaxConnectionsPerWindow(0); // Disabled
        assertEquals(0, config.getMaxConnectionsPerWindow());
    }

    @Test
    void testDefaultMaxCommandsPerMinute() {
        assertEquals(100, config.getMaxCommandsPerMinute(),
            "Default maxCommandsPerMinute should be 100");
    }

    @Test
    void testSetMaxCommandsPerMinute() {
        config.setMaxCommandsPerMinute(200);
        assertEquals(200, config.getMaxCommandsPerMinute());

        config.setMaxCommandsPerMinute(0); // Disabled
        assertEquals(0, config.getMaxCommandsPerMinute());
    }

    @Test
    void testDefaultMinDataRateBytesPerSecond() {
        assertEquals(10240, config.getMinDataRateBytesPerSecond(),
            "Default minDataRateBytesPerSecond should be 10240 (10 KB/s)");
    }

    @Test
    void testSetMinDataRateBytesPerSecond() {
        config.setMinDataRateBytesPerSecond(20480); // 20 KB/s
        assertEquals(20480, config.getMinDataRateBytesPerSecond());

        config.setMinDataRateBytesPerSecond(0); // Disabled
        assertEquals(0, config.getMinDataRateBytesPerSecond());
    }

    @Test
    void testDefaultMaxDataTimeoutSeconds() {
        assertEquals(300, config.getMaxDataTimeoutSeconds(),
            "Default maxDataTimeoutSeconds should be 300 (5 minutes)");
    }

    @Test
    void testSetMaxDataTimeoutSeconds() {
        config.setMaxDataTimeoutSeconds(600); // 10 minutes
        assertEquals(600, config.getMaxDataTimeoutSeconds());

        config.setMaxDataTimeoutSeconds(0); // Disabled
        assertEquals(0, config.getMaxDataTimeoutSeconds());
    }

    @Test
    void testDefaultTarpitDelayMillis() {
        assertEquals(1000, config.getTarpitDelayMillis(),
            "Default tarpitDelayMillis should be 1000 (1 second)");
    }

    @Test
    void testSetTarpitDelayMillis() {
        config.setTarpitDelayMillis(5000); // 5 seconds
        assertEquals(5000, config.getTarpitDelayMillis());

        config.setTarpitDelayMillis(0); // Disabled
        assertEquals(0, config.getTarpitDelayMillis());
    }

    @Test
    void testHighSecurityProfile() {
        // Configure for high security (under attack)
        config.setDosProtectionEnabled(true);
        config.setMaxConnectionsPerIp(3);
        config.setMaxTotalConnections(50);
        config.setMaxConnectionsPerWindow(10);
        config.setMaxCommandsPerMinute(50);
        config.setTarpitDelayMillis(5000);

        assertTrue(config.isDosProtectionEnabled());
        assertEquals(3, config.getMaxConnectionsPerIp());
        assertEquals(50, config.getMaxTotalConnections());
        assertEquals(10, config.getMaxConnectionsPerWindow());
        assertEquals(50, config.getMaxCommandsPerMinute());
        assertEquals(5000, config.getTarpitDelayMillis());
    }

    @Test
    void testHighVolumeProfile() {
        // Configure for high volume legitimate traffic
        config.setDosProtectionEnabled(true);
        config.setMaxConnectionsPerIp(50);
        config.setMaxTotalConnections(500);
        config.setMaxConnectionsPerWindow(100);
        config.setMaxCommandsPerMinute(200);
        config.setMinDataRateBytesPerSecond(5120); // 5 KB/s
        config.setMaxDataTimeoutSeconds(600); // 10 minutes
        config.setTarpitDelayMillis(500);

        assertTrue(config.isDosProtectionEnabled());
        assertEquals(50, config.getMaxConnectionsPerIp());
        assertEquals(500, config.getMaxTotalConnections());
        assertEquals(100, config.getMaxConnectionsPerWindow());
        assertEquals(200, config.getMaxCommandsPerMinute());
        assertEquals(5120, config.getMinDataRateBytesPerSecond());
        assertEquals(600, config.getMaxDataTimeoutSeconds());
        assertEquals(500, config.getTarpitDelayMillis());
    }

    @Test
    void testDisabledProtectionProfile() {
        // Disable all protections for testing
        config.setDosProtectionEnabled(false);
        config.setMaxConnectionsPerIp(0);
        config.setMaxTotalConnections(0);
        config.setMaxConnectionsPerWindow(0);
        config.setMaxCommandsPerMinute(0);
        config.setMinDataRateBytesPerSecond(0);
        config.setMaxDataTimeoutSeconds(0);
        config.setTarpitDelayMillis(0);

        assertFalse(config.isDosProtectionEnabled());
        assertEquals(0, config.getMaxConnectionsPerIp());
        assertEquals(0, config.getMaxTotalConnections());
        assertEquals(0, config.getMaxConnectionsPerWindow());
        assertEquals(0, config.getMaxCommandsPerMinute());
        assertEquals(0, config.getMinDataRateBytesPerSecond());
        assertEquals(0, config.getMaxDataTimeoutSeconds());
        assertEquals(0, config.getTarpitDelayMillis());
    }

    @Test
    void testNegativeValuesHandling() {
        // Verify negative values are accepted (interpreted as disabled/invalid)
        assertDoesNotThrow(() -> config.setMaxConnectionsPerIp(-1));
        assertDoesNotThrow(() -> config.setMaxTotalConnections(-1));
        assertDoesNotThrow(() -> config.setMaxConnectionsPerWindow(-1));
        assertDoesNotThrow(() -> config.setMaxCommandsPerMinute(-1));
        assertDoesNotThrow(() -> config.setMinDataRateBytesPerSecond(-1));
        assertDoesNotThrow(() -> config.setMaxDataTimeoutSeconds(-1));
        assertDoesNotThrow(() -> config.setTarpitDelayMillis(-1));
    }

    @Test
    void testVeryLargeValues() {
        // Test with very large values
        config.setMaxConnectionsPerIp(Integer.MAX_VALUE);
        config.setMaxTotalConnections(Integer.MAX_VALUE);
        config.setMaxConnectionsPerWindow(Integer.MAX_VALUE);
        config.setMaxCommandsPerMinute(Integer.MAX_VALUE);
        config.setMinDataRateBytesPerSecond(Integer.MAX_VALUE);
        config.setMaxDataTimeoutSeconds(Integer.MAX_VALUE);
        config.setTarpitDelayMillis(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, config.getMaxConnectionsPerIp());
        assertEquals(Integer.MAX_VALUE, config.getMaxTotalConnections());
        assertEquals(Integer.MAX_VALUE, config.getMaxConnectionsPerWindow());
        assertEquals(Integer.MAX_VALUE, config.getMaxCommandsPerMinute());
        assertEquals(Integer.MAX_VALUE, config.getMinDataRateBytesPerSecond());
        assertEquals(Integer.MAX_VALUE, config.getMaxDataTimeoutSeconds());
        assertEquals(Integer.MAX_VALUE, config.getTarpitDelayMillis());
    }

    @Test
    void testChainedSetters() {
        // Verify fluent interface if implemented
        ListenerConfig result = config
            .setDosProtectionEnabled(true)
            .setMaxConnectionsPerIp(20)
            .setMaxTotalConnections(200)
            .setMaxConnectionsPerWindow(40)
            .setMaxCommandsPerMinute(150)
            .setMinDataRateBytesPerSecond(15360)
            .setMaxDataTimeoutSeconds(400)
            .setTarpitDelayMillis(2000);

        assertNotNull(result);
        assertTrue(config.isDosProtectionEnabled());
        assertEquals(20, config.getMaxConnectionsPerIp());
        assertEquals(200, config.getMaxTotalConnections());
    }

    @Test
    void testIndependentProperties() {
        // Verify setting one property doesn't affect others
        int originalMaxConnections = config.getMaxTotalConnections();

        config.setMaxConnectionsPerIp(15);

        assertEquals(15, config.getMaxConnectionsPerIp());
        assertEquals(originalMaxConnections, config.getMaxTotalConnections(),
            "Setting maxConnectionsPerIp should not affect maxTotalConnections");
    }

    @Test
    void testBoundaryValues() {
        // Test boundary values
        config.setMaxConnectionsPerIp(1);
        assertEquals(1, config.getMaxConnectionsPerIp());

        config.setRateLimitWindowSeconds(1);
        assertEquals(1, config.getRateLimitWindowSeconds());

        config.setTarpitDelayMillis(1);
        assertEquals(1, config.getTarpitDelayMillis());
    }

    @Test
    void testMultipleUpdates() {
        // Update same property multiple times
        config.setMaxConnectionsPerIp(5);
        assertEquals(5, config.getMaxConnectionsPerIp());

        config.setMaxConnectionsPerIp(10);
        assertEquals(10, config.getMaxConnectionsPerIp());

        config.setMaxConnectionsPerIp(15);
        assertEquals(15, config.getMaxConnectionsPerIp());
    }
}

