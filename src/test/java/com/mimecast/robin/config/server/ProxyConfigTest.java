package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("rcpt", ".*@example\\.com");
        ruleMap.put("hosts", List.of("relay.example.com"));

        Map<String, Object> map = new HashMap<>();
        map.put("rules", List.of(ruleMap));
        ProxyConfig config = new ProxyConfig(map);

        assertEquals(1, config.getRules().size());
        ProxyRule rule = config.getRules().get(0);
        assertEquals(".*@example\\.com", rule.getRcpt());
        assertEquals("relay.example.com", rule.getHost());
        assertEquals(1, rule.getHosts().size());
    }

    @Test
    void testGetRulesEmpty() {
        ProxyConfig config = new ProxyConfig(null);
        assertTrue(config.getRules().isEmpty());
    }

    @Test
    void testGetRulesWithAuthentication() {
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("rcpt", ".*@example\\.com");
        ruleMap.put("hosts", List.of("relay.example.com"));
        ruleMap.put("port", 587);
        ruleMap.put("tls", true);
        ruleMap.put("authUsername", "user");
        ruleMap.put("authPassword", "pass");
        ruleMap.put("authMechanism", "PLAIN");

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(ruleMap));

        ProxyConfig config = new ProxyConfig(configMap);

        assertEquals(1, config.getRules().size());
        ProxyRule rule = config.getRules().get(0);
        assertEquals("relay.example.com", rule.getHost());
        assertEquals(1, rule.getHosts().size());
        assertEquals(587, rule.getPort());
        assertTrue(rule.isTls());
        assertTrue(rule.hasAuth());
        assertEquals("user", rule.getAuthUsername());
        assertEquals("pass", rule.getAuthPassword());
        assertEquals("PLAIN", rule.getAuthMechanism());
    }

    @Test
    void testGetRulesSkipsInvalidRules() {
        Map<String, Object> validRule = new HashMap<>();
        validRule.put("hosts", List.of("valid.example.com"));

        Map<String, Object> invalidRule = new HashMap<>();
        // Missing hosts field - should be skipped

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);

        // Use ArrayList to allow null elements (List.of() doesn't allow null)
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(validRule);
        rules.add(invalidRule);
        rules.add(null);
        configMap.put("rules", rules);

        ProxyConfig config = new ProxyConfig(configMap);

        // Only the valid rule should be present
        assertEquals(1, config.getRules().size());
        assertEquals("valid.example.com", config.getRules().get(0).getHost());
    }

    @Test
    void testGetRulesWithMultipleHosts() {
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("rcpt", ".*@example\\.com");
        ruleMap.put("hosts", List.of("relay1.example.com", "relay2.example.com", "relay3.example.com"));

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(ruleMap));

        ProxyConfig config = new ProxyConfig(configMap);

        assertEquals(1, config.getRules().size());
        ProxyRule rule = config.getRules().get(0);
        assertEquals(3, rule.getHosts().size());
        assertEquals("relay1.example.com", rule.getHost()); // First host
        assertEquals("relay2.example.com", rule.getHosts().get(1));
        assertEquals("relay3.example.com", rule.getHosts().get(2));
    }

    @Test
    void testGetRulesWithDirection() {
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("hosts", List.of("relay.example.com"));
        ruleMap.put("direction", "inbound");

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(ruleMap));

        ProxyConfig config = new ProxyConfig(configMap);

        assertEquals(1, config.getRules().size());
        ProxyRule rule = config.getRules().get(0);
        assertEquals("inbound", rule.getDirection());
        assertTrue(rule.matchesDirection(true));   // inbound
        assertFalse(rule.matchesDirection(false)); // not outbound
    }
}
