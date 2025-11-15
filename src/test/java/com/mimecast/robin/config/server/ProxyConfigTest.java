package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyConfig tests.
 */
class ProxyConfigTest {

    @Test
    void testIsEnabled() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        ProxyConfig config = new ProxyConfig(map);
        assertTrue(config.isEnabled());
    }

    @Test
    void testIsEnabledDefault() {
        ProxyConfig config = new ProxyConfig(null);
        assertFalse(config.isEnabled());
    }

    @Test
    void testIsEnabledFalse() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        ProxyConfig config = new ProxyConfig(map);
        assertFalse(config.isEnabled());
    }

    @Test
    void testGetRules() {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> rules = List.of(
            Map.of("rcpt", ".*@example\\.com", "host", "relay.example.com")
        );
        map.put("rules", rules);
        ProxyConfig config = new ProxyConfig(map);
        
        assertEquals(1, config.getRules().size());
        assertEquals(".*@example\\.com", config.getRules().get(0).get("rcpt"));
    }

    @Test
    void testGetRulesEmpty() {
        ProxyConfig config = new ProxyConfig(null);
        assertTrue(config.getRules().isEmpty());
    }
}
