package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.WhitelistConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WhitelistMatcher.
 */
class WhitelistMatcherTest {

    @Test
    void testDisabledWhitelistAllowsNone() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        map.put("entries", Arrays.asList("127.0.0.1", "10.0.0.0/8"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(WhitelistMatcher.isWhitelisted("127.0.0.1", config), "Disabled whitelist should not match");
        assertFalse(WhitelistMatcher.isWhitelisted("10.0.0.5", config), "Disabled whitelist should not match");
    }

    @Test
    void testEmptyWhitelistAllowsNone() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Collections.emptyList());

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(WhitelistMatcher.isWhitelisted("127.0.0.1", config), "Empty whitelist should not match");
    }

    @Test
    void testSingleIPv4Match() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("127.0.0.1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("127.0.0.1", config), "Should whitelist exact match");
        assertFalse(WhitelistMatcher.isWhitelisted("127.0.0.2", config), "Should not whitelist different IP");
    }

    @Test
    void testIPv4CIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("10.0.0.0/8"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("10.0.0.1", config), "Should whitelist IP in range");
        assertTrue(WhitelistMatcher.isWhitelisted("10.255.255.254", config), "Should whitelist IP in range");
        assertFalse(WhitelistMatcher.isWhitelisted("11.0.0.1", config), "Should not whitelist IP outside range");
    }

    @Test
    void testIPv4SubnetMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/24"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("192.168.1.1", config), "Should whitelist IP in subnet");
        assertTrue(WhitelistMatcher.isWhitelisted("192.168.1.254", config), "Should whitelist IP in subnet");
        assertFalse(WhitelistMatcher.isWhitelisted("192.168.2.1", config), "Should not whitelist IP in different subnet");
    }

    @Test
    void testSingleIPv6Match() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("::1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("::1", config), "Should whitelist IPv6 loopback");
        assertFalse(WhitelistMatcher.isWhitelisted("::2", config), "Should not whitelist different IPv6");
    }

    @Test
    void testIPv6CIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("2001:db8::/32"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("2001:db8::1", config), "Should whitelist IPv6 in range");
        assertFalse(WhitelistMatcher.isWhitelisted("2001:db9::1", config), "Should not whitelist IPv6 outside range");
    }

    @Test
    void testMultipleEntries() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("127.0.0.1", "10.0.0.0/8", "2001:db8::/32"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("127.0.0.1", config), "Should whitelist first entry");
        assertTrue(WhitelistMatcher.isWhitelisted("10.50.0.1", config), "Should whitelist second entry");
        assertTrue(WhitelistMatcher.isWhitelisted("2001:db8::1", config), "Should whitelist third entry");
        assertFalse(WhitelistMatcher.isWhitelisted("8.8.8.8", config), "Should not whitelist unlisted IP");
    }

    @Test
    void testInvalidIPAddress() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("127.0.0.1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(WhitelistMatcher.isWhitelisted("invalid-ip", config), "Invalid IP should not be whitelisted");
    }

    @Test
    void testNullIPAddress() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("127.0.0.1"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(WhitelistMatcher.isWhitelisted(null, config), "Null IP should not be whitelisted");
    }

    @Test
    void testIPv4IPv6Mismatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("10.0.0.0/8"));

        WhitelistConfig config = new WhitelistConfig(map);
        assertFalse(WhitelistMatcher.isWhitelisted("2001:db8::1", config), "IPv6 should not match IPv4 CIDR");
    }

    @Test
    void testEntryWithWhitespace() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("  127.0.0.1  "));

        WhitelistConfig config = new WhitelistConfig(map);
        assertTrue(WhitelistMatcher.isWhitelisted("127.0.0.1", config), "Should handle whitespace in entries");
    }
}
