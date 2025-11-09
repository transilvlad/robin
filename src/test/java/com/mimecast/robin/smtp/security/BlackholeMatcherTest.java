package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.BlackholeConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlackholeMatcher.
 */
class BlackholeMatcherTest {

    @Test
    void testDisabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", "test.com", "sender@test.com", "rcpt@test.com", config));
    }

    @Test
    void testEmptyRules() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("rules", new ArrayList<>());
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", "test.com", "sender@test.com", "rcpt@test.com", config));
    }

    @Test
    void testIpMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\.1\\..*");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertTrue(BlackholeMatcher.shouldBlackhole("192.168.1.100", null, null, null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole("10.0.0.1", null, null, null, config));
    }

    @Test
    void testEhloMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ehlo", ".*\\.spam\\.com");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertTrue(BlackholeMatcher.shouldBlackhole(null, "mail.spam.com", null, null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole(null, "mail.legitimate.com", null, null, config));
    }

    @Test
    void testMailMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("mail", "spammer@.*");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertTrue(BlackholeMatcher.shouldBlackhole(null, null, "spammer@evil.com", null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole(null, null, "user@legitimate.com", null, config));
    }

    @Test
    void testRcptMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("rcpt", ".*@honeypot\\.com");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        assertTrue(BlackholeMatcher.shouldBlackhole(null, null, null, "anyone@honeypot.com", config));
        assertFalse(BlackholeMatcher.shouldBlackhole(null, null, null, "user@legitimate.com", config));
    }

    @Test
    void testMultipleConditions() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\..*");
        rule.put("mail", ".*@spam\\.com");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        // Both conditions must match
        assertTrue(BlackholeMatcher.shouldBlackhole("192.168.1.1", null, "spammer@spam.com", null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", null, "user@legitimate.com", null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole("10.0.0.1", null, "spammer@spam.com", null, config));
    }

    @Test
    void testMultipleRules() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        
        Map<String, String> rule1 = new HashMap<>();
        rule1.put("ip", "192\\.168\\..*");
        rules.add(rule1);
        
        Map<String, String> rule2 = new HashMap<>();
        rule2.put("mail", ".*@spam\\.com");
        rules.add(rule2);
        
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        // Either rule can match
        assertTrue(BlackholeMatcher.shouldBlackhole("192.168.1.1", null, null, null, config));
        assertTrue(BlackholeMatcher.shouldBlackhole("10.0.0.1", null, "spammer@spam.com", null, config));
        assertFalse(BlackholeMatcher.shouldBlackhole("10.0.0.1", null, "user@legitimate.com", null, config));
    }

    @Test
    void testComplexRule() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\..*");
        rule.put("ehlo", ".*\\.test\\.com");
        rule.put("mail", ".*@sender\\.com");
        rule.put("rcpt", ".*@recipient\\.com");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        // All conditions must match
        assertTrue(BlackholeMatcher.shouldBlackhole("192.168.1.1", "mail.test.com", "user@sender.com", "user@recipient.com", config));
        assertFalse(BlackholeMatcher.shouldBlackhole("10.0.0.1", "mail.test.com", "user@sender.com", "user@recipient.com", config));
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", "mail.other.com", "user@sender.com", "user@recipient.com", config));
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", "mail.test.com", "user@other.com", "user@recipient.com", config));
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", "mail.test.com", "user@sender.com", "user@other.com", config));
    }

    @Test
    void testInvalidRegex() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ip", "[invalid(regex");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        // Invalid regex should not match
        assertFalse(BlackholeMatcher.shouldBlackhole("192.168.1.1", null, null, null, config));
    }

    @Test
    void testNullValues() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        
        List<Map<String, String>> rules = new ArrayList<>();
        Map<String, String> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\..*");
        rules.add(rule);
        map.put("rules", rules);
        
        BlackholeConfig config = new BlackholeConfig(map);
        
        // Null IP should not match
        assertFalse(BlackholeMatcher.shouldBlackhole(null, null, null, null, config));
    }
}
