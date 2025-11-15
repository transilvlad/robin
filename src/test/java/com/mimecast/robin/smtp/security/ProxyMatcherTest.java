package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyMatcher tests.
 */
class ProxyMatcherTest {

    @Test
    void testFindMatchingRuleDisabled() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", false);
        ProxyConfig config = new ProxyConfig(configMap);
        
        Optional<Map<String, Object>> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", config
        );
        
        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleNoRules() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        ProxyConfig config = new ProxyConfig(configMap);
        
        Optional<Map<String, Object>> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", config
        );
        
        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleRcptMatch() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("rcpt", ".*@proxy\\.example\\.com");
        rule.put("host", "relay.example.com");
        
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);
        
        Optional<Map<String, Object>> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@proxy.example.com", config
        );
        
        assertTrue(result.isPresent());
        assertEquals("relay.example.com", result.get().get("host"));
    }

    @Test
    void testFindMatchingRuleRcptNoMatch() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("rcpt", ".*@proxy\\.example\\.com");
        rule.put("host", "relay.example.com");
        
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);
        
        Optional<Map<String, Object>> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@other.example.com", config
        );
        
        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleMultipleConditions() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\..*");
        rule.put("mail", ".*@sender\\.example\\.com");
        rule.put("rcpt", ".*@recipient\\.example\\.com");
        rule.put("host", "relay.example.com");
        
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);
        
        // All conditions match
        Optional<Map<String, Object>> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@sender.example.com", "recipient@recipient.example.com", config
        );
        assertTrue(result.isPresent());
        
        // IP doesn't match
        result = ProxyMatcher.findMatchingRule(
            "10.0.0.1", "example.com", "sender@sender.example.com", "recipient@recipient.example.com", config
        );
        assertFalse(result.isPresent());
        
        // MAIL doesn't match
        result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@other.example.com", "recipient@recipient.example.com", config
        );
        assertFalse(result.isPresent());
    }

    @Test
    void testGetAction() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("action", "accept");
        assertEquals("accept", ProxyMatcher.getAction(rule));
        
        rule.put("action", "reject");
        assertEquals("reject", ProxyMatcher.getAction(rule));
        
        rule.put("action", "none");
        assertEquals("none", ProxyMatcher.getAction(rule));
        
        rule.put("action", "invalid");
        assertEquals("none", ProxyMatcher.getAction(rule));
        
        Map<String, Object> emptyRule = new HashMap<>();
        assertEquals("none", ProxyMatcher.getAction(emptyRule));
    }

    @Test
    void testGetHost() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("host", "relay.example.com");
        assertEquals("relay.example.com", ProxyMatcher.getHost(rule));
        
        Map<String, Object> emptyRule = new HashMap<>();
        assertEquals("localhost", ProxyMatcher.getHost(emptyRule));
    }

    @Test
    void testGetPort() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("port", 587);
        assertEquals(587, ProxyMatcher.getPort(rule));
        
        rule.put("port", "2525");
        assertEquals(2525, ProxyMatcher.getPort(rule));
        
        Map<String, Object> emptyRule = new HashMap<>();
        assertEquals(25, ProxyMatcher.getPort(emptyRule));
    }

    @Test
    void testGetProtocol() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("protocol", "smtp");
        assertEquals("smtp", ProxyMatcher.getProtocol(rule));
        
        rule.put("protocol", "esmtp");
        assertEquals("esmtp", ProxyMatcher.getProtocol(rule));
        
        rule.put("protocol", "lmtp");
        assertEquals("lmtp", ProxyMatcher.getProtocol(rule));
        
        rule.put("protocol", "SMTP");
        assertEquals("smtp", ProxyMatcher.getProtocol(rule));
        
        Map<String, Object> emptyRule = new HashMap<>();
        assertEquals("esmtp", ProxyMatcher.getProtocol(emptyRule));
    }

    @Test
    void testIsTls() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("tls", true);
        assertTrue(ProxyMatcher.isTls(rule));
        
        rule.put("tls", false);
        assertFalse(ProxyMatcher.isTls(rule));
        
        rule.put("tls", "true");
        assertTrue(ProxyMatcher.isTls(rule));
        
        Map<String, Object> emptyRule = new HashMap<>();
        assertFalse(ProxyMatcher.isTls(emptyRule));
    }
}
