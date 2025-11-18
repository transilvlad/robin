package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.ProxyConfig;
import com.mimecast.robin.config.server.ProxyRule;
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

        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", true, config
        );

        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleNoRules() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        ProxyConfig config = new ProxyConfig(configMap);

        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", true, config
        );

        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleRcptMatch() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("rcpt", ".*@proxy\\.example\\.com");
        rule.put("hosts", List.of("relay.example.com"));

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);

        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@proxy.example.com", true, config
        );

        assertTrue(result.isPresent());
        assertEquals("relay.example.com", result.get().getHost());
        assertEquals(1, result.get().getHosts().size());
    }

    @Test
    void testFindMatchingRuleRcptNoMatch() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("rcpt", ".*@proxy\\.example\\.com");
        rule.put("hosts", List.of("relay.example.com"));

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);

        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@other.example.com", true, config
        );

        assertFalse(result.isPresent());
    }

    @Test
    void testFindMatchingRuleMultipleConditions() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("ip", "192\\.168\\..*");
        rule.put("mail", ".*@sender\\.example\\.com");
        rule.put("rcpt", ".*@recipient\\.example\\.com");
        rule.put("hosts", List.of("relay.example.com"));

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule));
        ProxyConfig config = new ProxyConfig(configMap);

        // All conditions match
        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@sender.example.com", "recipient@recipient.example.com", true, config
        );
        assertTrue(result.isPresent());

        // IP doesn't match
        result = ProxyMatcher.findMatchingRule(
            "10.0.0.1", "example.com", "sender@sender.example.com", "recipient@recipient.example.com", true, config
        );
        assertFalse(result.isPresent());

        // MAIL doesn't match
        result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@other.example.com", "recipient@recipient.example.com", true, config
        );
        assertFalse(result.isPresent());
    }

    @Test
    void testGetAction() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("localhost"));
        rule.put("action", "accept");
        ProxyRule proxyRule = new ProxyRule(rule);
        assertEquals("accept", proxyRule.getAction());

        rule.put("action", "reject");
        proxyRule = new ProxyRule(rule);
        assertEquals("reject", proxyRule.getAction());

        rule.put("action", "none");
        proxyRule = new ProxyRule(rule);
        assertEquals("none", proxyRule.getAction());

        rule.put("action", "invalid");
        proxyRule = new ProxyRule(rule);
        assertEquals("none", proxyRule.getAction());

        Map<String, Object> emptyRule = new HashMap<>();
        emptyRule.put("hosts", List.of("localhost"));
        proxyRule = new ProxyRule(emptyRule);
        assertEquals("none", proxyRule.getAction());
    }

    @Test
    void testGetHost() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("relay.example.com"));
        ProxyRule proxyRule = new ProxyRule(rule);
        assertEquals("relay.example.com", proxyRule.getHost());
        assertEquals(1, proxyRule.getHosts().size());

        Map<String, Object> multiHostRule = new HashMap<>();
        multiHostRule.put("hosts", List.of("relay1.example.com", "relay2.example.com"));
        proxyRule = new ProxyRule(multiHostRule);
        assertEquals("relay1.example.com", proxyRule.getHost());
        assertEquals(2, proxyRule.getHosts().size());
        assertEquals("relay2.example.com", proxyRule.getHosts().get(1));
    }

    @Test
    void testGetPort() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("localhost"));
        rule.put("port", 587);
        ProxyRule proxyRule = new ProxyRule(rule);
        assertEquals(587, proxyRule.getPort());

        rule.put("port", "2525");
        proxyRule = new ProxyRule(rule);
        assertEquals(2525, proxyRule.getPort());

        Map<String, Object> emptyRule = new HashMap<>();
        emptyRule.put("hosts", List.of("localhost"));
        proxyRule = new ProxyRule(emptyRule);
        assertEquals(25, proxyRule.getPort());
    }

    @Test
    void testGetProtocol() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("localhost"));
        rule.put("protocol", "smtp");
        ProxyRule proxyRule = new ProxyRule(rule);
        assertEquals("smtp", proxyRule.getProtocol());

        rule.put("protocol", "esmtp");
        proxyRule = new ProxyRule(rule);
        assertEquals("esmtp", proxyRule.getProtocol());

        rule.put("protocol", "lmtp");
        proxyRule = new ProxyRule(rule);
        assertEquals("lmtp", proxyRule.getProtocol());

        rule.put("protocol", "SMTP");
        proxyRule = new ProxyRule(rule);
        assertEquals("smtp", proxyRule.getProtocol());

        Map<String, Object> emptyRule = new HashMap<>();
        emptyRule.put("hosts", List.of("localhost"));
        proxyRule = new ProxyRule(emptyRule);
        assertEquals("esmtp", proxyRule.getProtocol());
    }

    @Test
    void testIsTls() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("localhost"));
        rule.put("tls", true);
        ProxyRule proxyRule = new ProxyRule(rule);
        assertTrue(proxyRule.isTls());

        rule.put("tls", false);
        proxyRule = new ProxyRule(rule);
        assertFalse(proxyRule.isTls());

        rule.put("tls", "true");
        proxyRule = new ProxyRule(rule);
        assertTrue(proxyRule.isTls());

        Map<String, Object> emptyRule = new HashMap<>();
        emptyRule.put("hosts", List.of("localhost"));
        proxyRule = new ProxyRule(emptyRule);
        assertFalse(proxyRule.isTls());
    }

    @Test
    void testMultipleMatchingRulesWarning() {
        // Create two rules that will both match the same recipient.
        Map<String, Object> rule1 = new HashMap<>();
        rule1.put("rcpt", ".*@example\\.com");
        rule1.put("hosts", List.of("relay1.example.com"));
        rule1.put("port", 25);

        Map<String, Object> rule2 = new HashMap<>();
        rule2.put("rcpt", ".*@example\\.com");
        rule2.put("hosts", List.of("relay2.example.com"));
        rule2.put("port", 26);

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(rule1, rule2));
        ProxyConfig config = new ProxyConfig(configMap);

        // Both rules match, but only first should be returned.
        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", true, config
        );

        assertTrue(result.isPresent());
        assertEquals("relay1.example.com", result.get().getHost());
        assertEquals(25, result.get().getPort());

        // The second matching rule should trigger a warning log (verified manually or with log capture).
        // This test verifies the functional behavior: only first rule is returned.
    }

    @Test
    void testAuthenticationConfiguration() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("relay.example.com"));
        rule.put("authUsername", "testuser");
        rule.put("authPassword", "testpass");
        rule.put("authMechanism", "PLAIN");

        ProxyRule proxyRule = new ProxyRule(rule);

        assertTrue(proxyRule.hasAuth());
        assertEquals("testuser", proxyRule.getAuthUsername());
        assertEquals("testpass", proxyRule.getAuthPassword());
        assertEquals("PLAIN", proxyRule.getAuthMechanism());
    }

    @Test
    void testNoAuthenticationConfiguration() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("relay.example.com"));

        ProxyRule proxyRule = new ProxyRule(rule);

        assertFalse(proxyRule.hasAuth());
        assertNull(proxyRule.getAuthUsername());
        assertNull(proxyRule.getAuthPassword());
        assertEquals("PLAIN", proxyRule.getAuthMechanism()); // Default mechanism.
    }

    @Test
    void testDirectionFiltering() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of("localhost"));

        // Test default direction (both)
        ProxyRule proxyRule = new ProxyRule(rule);
        assertEquals("both", proxyRule.getDirection());
        assertTrue(proxyRule.matchesDirection(true));  // inbound
        assertTrue(proxyRule.matchesDirection(false)); // outbound

        // Test inbound direction
        rule.put("direction", "inbound");
        proxyRule = new ProxyRule(rule);
        assertEquals("inbound", proxyRule.getDirection());
        assertTrue(proxyRule.matchesDirection(true));   // matches inbound
        assertFalse(proxyRule.matchesDirection(false)); // doesn't match outbound

        // Test outbound direction
        rule.put("direction", "outbound");
        proxyRule = new ProxyRule(rule);
        assertEquals("outbound", proxyRule.getDirection());
        assertFalse(proxyRule.matchesDirection(true));  // doesn't match inbound
        assertTrue(proxyRule.matchesDirection(false));  // matches outbound

        // Test both direction explicitly
        rule.put("direction", "both");
        proxyRule = new ProxyRule(rule);
        assertEquals("both", proxyRule.getDirection());
        assertTrue(proxyRule.matchesDirection(true));
        assertTrue(proxyRule.matchesDirection(false));
    }

    @Test
    void testDirectionFilteringInProxyMatcher() {
        Map<String, Object> inboundRule = new HashMap<>();
        inboundRule.put("rcpt", ".*@example\\.com");
        inboundRule.put("hosts", List.of("relay.example.com"));
        inboundRule.put("direction", "inbound");

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("enabled", true);
        configMap.put("rules", List.of(inboundRule));
        ProxyConfig config = new ProxyConfig(configMap);

        // Should match for inbound connection
        Optional<ProxyRule> result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", true, config
        );
        assertTrue(result.isPresent());

        // Should NOT match for outbound connection
        result = ProxyMatcher.findMatchingRule(
            "192.168.1.1", "example.com", "sender@example.com", "recipient@example.com", false, config
        );
        assertFalse(result.isPresent());
    }

    @Test
    void testProxyRuleEqualsAndHashCode() {
        // Create two rules with identical destination config
        Map<String, Object> rule1Map = new HashMap<>();
        rule1Map.put("hosts", List.of("relay.example.com"));
        rule1Map.put("port", 25);
        rule1Map.put("protocol", "esmtp");
        rule1Map.put("tls", false);
        rule1Map.put("authUsername", "user");
        rule1Map.put("authPassword", "pass");

        Map<String, Object> rule2Map = new HashMap<>();
        rule2Map.put("hosts", List.of("relay.example.com"));
        rule2Map.put("port", 25);
        rule2Map.put("protocol", "esmtp");
        rule2Map.put("tls", false);
        rule2Map.put("authUsername", "user");
        rule2Map.put("authPassword", "pass");

        ProxyRule rule1 = new ProxyRule(rule1Map);
        ProxyRule rule2 = new ProxyRule(rule2Map);

        // Rules with same destination should be equal (for connection pooling)
        assertEquals(rule1, rule2);
        assertEquals(rule1.hashCode(), rule2.hashCode());

        // Different host should not be equal
        Map<String, Object> rule3Map = new HashMap<>();
        rule3Map.put("hosts", List.of("different.example.com"));
        rule3Map.put("port", 25);
        ProxyRule rule3 = new ProxyRule(rule3Map);

        assertNotEquals(rule1, rule3);
    }

    @Test
    void testEmptyHostsList() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("hosts", List.of()); // Empty hosts list

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new ProxyRule(rule));
    }

    @Test
    void testMissingHostsList() {
        Map<String, Object> rule = new HashMap<>();
        // No hosts field

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new ProxyRule(rule));
    }
}
