package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DistributedRateConfig.
 */
class DistributedRateConfigTest {

    @Test
    void testDefaultConfigDisabled() {
        DistributedRateConfig config = new DistributedRateConfig(null);
        assertFalse(config.isEnabled(), "Default config should be disabled");
        assertEquals("localhost", config.getHost());
        assertEquals(6379, config.getPort());
        assertEquals("", config.getPassword());
        assertEquals("robin:conn:", config.getKeyPrefix());
        assertEquals(10, config.getPoolMaxTotal());
        assertEquals(600, config.getKeyTtlSeconds());
    }

    @Test
    void testEnabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("host", "redis.example.com");
        map.put("port", 6380L);
        map.put("password", "secret");
        map.put("keyPrefix", "myapp:conn:");
        map.put("poolMaxTotal", 20L);
        map.put("keyTtlSeconds", 300L);

        DistributedRateConfig config = new DistributedRateConfig(map);
        assertTrue(config.isEnabled());
        assertEquals("redis.example.com", config.getHost());
        assertEquals(6380, config.getPort());
        assertEquals("secret", config.getPassword());
        assertEquals("myapp:conn:", config.getKeyPrefix());
        assertEquals(20, config.getPoolMaxTotal());
        assertEquals(300, config.getKeyTtlSeconds());
    }

    @Test
    void testPartialConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("host", "redis.local");

        DistributedRateConfig config = new DistributedRateConfig(map);
        assertTrue(config.isEnabled());
        assertEquals("redis.local", config.getHost());
        assertEquals(6379, config.getPort(), "Port should default to 6379");
        assertEquals("", config.getPassword(), "Password should default to empty");
        assertEquals("robin:conn:", config.getKeyPrefix(), "Prefix should default");
    }
}
