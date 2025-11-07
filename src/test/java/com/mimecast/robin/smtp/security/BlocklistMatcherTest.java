package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.BlocklistConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlocklistMatcher.
 */
class BlocklistMatcherTest {

    @Test
    void testDisabledBlocklistAllowsAll() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);
        map.put("entries", Arrays.asList("192.168.1.1", "10.0.0.0/8"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.1", config), "Disabled blocklist should allow all");
        assertFalse(BlocklistMatcher.isBlocked("10.0.0.5", config), "Disabled blocklist should allow all");
    }

    @Test
    void testEmptyBlocklistAllowsAll() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Collections.emptyList());
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.1", config), "Empty blocklist should allow all");
    }

    @Test
    void testSingleIPv4Match() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.100"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.100", config), "Should block exact match");
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.101", config), "Should not block different IP");
    }

    @Test
    void testIPv4CIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/24"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.1", config), "Should block IP in range");
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.100", config), "Should block IP in range");
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.254", config), "Should block IP in range");
        assertFalse(BlocklistMatcher.isBlocked("192.168.2.1", config), "Should not block IP outside range");
    }

    @Test
    void testIPv4LargeCIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("10.0.0.0/8"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("10.0.0.1", config), "Should block IP in range");
        assertTrue(BlocklistMatcher.isBlocked("10.255.255.254", config), "Should block IP in range");
        assertFalse(BlocklistMatcher.isBlocked("11.0.0.1", config), "Should not block IP outside range");
    }

    @Test
    void testIPv4SmallCIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/28"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.1", config), "Should block IP in range");
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.15", config), "Should block IP in range");
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.16", config), "Should not block IP outside range");
    }

    @Test
    void testSingleIPv6Match() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("2001:db8::1"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("2001:db8::1", config), "Should block exact IPv6 match");
        assertFalse(BlocklistMatcher.isBlocked("2001:db8::2", config), "Should not block different IPv6");
    }

    @Test
    void testIPv6CIDRMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("2001:db8::/32"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("2001:db8::1", config), "Should block IPv6 in range");
        assertTrue(BlocklistMatcher.isBlocked("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", config), "Should block IPv6 in range");
        assertFalse(BlocklistMatcher.isBlocked("2001:db9::1", config), "Should not block IPv6 outside range");
    }

    @Test
    void testMultipleEntries() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList(
            "192.168.1.100",
            "10.0.0.0/8",
            "2001:db8::/32"
        ));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.100", config), "Should block first entry");
        assertTrue(BlocklistMatcher.isBlocked("10.50.0.1", config), "Should block second entry");
        assertTrue(BlocklistMatcher.isBlocked("2001:db8::1", config), "Should block third entry");
        assertFalse(BlocklistMatcher.isBlocked("8.8.8.8", config), "Should not block unlisted IP");
    }

    @Test
    void testIPv4IPv6Mismatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/24"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("2001:db8::1", config), "IPv6 should not match IPv4 CIDR");
    }

    @Test
    void testInvalidIPAddress() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.100"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("invalid-ip", config), "Invalid IP should not block");
    }

    @Test
    void testInvalidCIDRNotation() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/99"));  // Invalid prefix
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.1", config), "Invalid CIDR should not block");
    }

    @Test
    void testNegativeCIDRPrefix() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.0/-1"));  // Negative prefix
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.1", config), "Negative CIDR prefix should not block");
    }

    @Test
    void testInvalidIPv6CIDRPrefix() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("2001:db8::/200"));  // Invalid IPv6 prefix (max 128)
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertFalse(BlocklistMatcher.isBlocked("2001:db8::1", config), "Invalid IPv6 CIDR should not block");
    }

    @Test
    void testEntryWithWhitespace() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("  192.168.1.100  "));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.100", config), "Should handle whitespace in entries");
    }

    @Test
    void testIPv4FullCIDR() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("192.168.1.100/32"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("192.168.1.100", config), "Should match /32 CIDR");
        assertFalse(BlocklistMatcher.isBlocked("192.168.1.101", config), "Should not match outside /32");
    }

    @Test
    void testIPv6FullCIDR() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("entries", Arrays.asList("2001:db8::1/128"));
        
        BlocklistConfig config = new BlocklistConfig(map);
        assertTrue(BlocklistMatcher.isBlocked("2001:db8::1", config), "Should match /128 CIDR");
        assertFalse(BlocklistMatcher.isBlocked("2001:db8::2", config), "Should not match outside /128");
    }
}
