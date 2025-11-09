package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlackholeConfig.
 */
class BlackholeConfigTest {

    @Test
    void testDefaultConfig() {
        BlackholeConfig config = new BlackholeConfig(null);
        assertFalse(config.isEnabled());
        assertTrue(config.getRules().isEmpty());
    }

    @Test
    void testEnabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        BlackholeConfig config = new BlackholeConfig(map);
        assertTrue(config.isEnabled());
    }

    @Test
    void testDisabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        
        BlackholeConfig config = new BlackholeConfig(map);
        assertFalse(config.isEnabled());
    }

    @Test
    void testRulesConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        
        Map<String, String> rule1 = new HashMap<>();
        rule1.put("ip", "192\\.168\\..*");
        rule1.put("mail", ".*@spam\\.com");
        rules.add(rule1);
        
        Map<String, String> rule2 = new HashMap<>();
        rule2.put("rcpt", ".*@test\\.com");
        rules.add(rule2);
        
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        assertTrue(config.isEnabled());
        assertEquals(2, config.getRules().size());
        assertEquals("192\\.168\\..*", config.getRules().get(0).get("ip"));
        assertEquals(".*@spam\\.com", config.getRules().get(0).get("mail"));
        assertEquals(".*@test\\.com", config.getRules().get(1).get("rcpt"));
    }

    @Test
    void testEmptyRulesConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("rules", new ArrayList<>());
        
        BlackholeConfig config = new BlackholeConfig(map);
        assertTrue(config.isEnabled());
        assertTrue(config.getRules().isEmpty());
    }
}
