package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WhitelistConfig.
 */
class WhitelistConfigTest {

    @Test
    void testDefaultConfigDisabled() {
        WhitelistConfig config = new WhitelistConfig(null);
        assertFalse(config.isEnabled(), "Default config should be disabled");
        assertTrue(config.getEntries().isEmpty(), "Default config should have empty entries");
    }

    @Test
    void testEnabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("127.0.0.1", "10.0.0.0/8"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(config.isEnabled(), "Config should be enabled");

        List<String> entries = config.getEntries();
        assertEquals(2, entries.size(), "Should have 2 entries");
        assertTrue(entries.contains("127.0.0.1"), "Should contain loopback IP");
        assertTrue(entries.contains("10.0.0.0/8"), "Should contain CIDR block");
    }

    @Test
    void testDisabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        map.put("entries", Arrays.asList("127.0.0.1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(config.isEnabled(), "Config should be disabled");
    }

    @Test
    void testEmptyEntries() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList());

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(config.isEnabled(), "Config should be enabled");
        assertTrue(config.getEntries().isEmpty(), "Entries should be empty");
    }

    @Test
    void testMissingEnabledKey() {
        Map<String, Object> map = new HashMap<>();
        map.put("entries", Arrays.asList("127.0.0.1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(config.isEnabled(), "Config without enabled key should be disabled");
    }

    @Test
    void testMissingEntriesKey() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(config.isEnabled(), "Config should be enabled");
        assertTrue(config.getEntries().isEmpty(), "Missing entries should return empty list");
    }
}
