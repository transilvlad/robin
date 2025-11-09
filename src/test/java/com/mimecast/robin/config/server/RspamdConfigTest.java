package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RspamdConfig.
 */
class RspamdConfigTest {

    @Test
    void testDefaultValues() {
        RspamdConfig config = new RspamdConfig(new HashMap<>());
        
        assertFalse(config.isEnabled(), "Default enabled should be false");
        assertEquals("localhost", config.getHost(), "Default host should be localhost");
        assertEquals(11333, config.getPort(), "Default port should be 11333");
        assertEquals(30, config.getTimeout(), "Default timeout should be 30");
        assertTrue(config.isSpfScanEnabled(), "Default SPF scan should be enabled");
        assertTrue(config.isDkimScanEnabled(), "Default DKIM scan should be enabled");
        assertTrue(config.isDmarcScanEnabled(), "Default DMARC scan should be enabled");
        assertEquals(7.0, config.getRejectThreshold(), "Default reject threshold should be 7.0");
        assertEquals(15.0, config.getDiscardThreshold(), "Default discard threshold should be 15.0");
    }

    @Test
    void testCustomValues() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("host", "rspamd.example.com");
        map.put("port", 9999);
        map.put("timeout", 60);
        map.put("spfScanEnabled", false);
        map.put("dkimScanEnabled", false);
        map.put("dmarcScanEnabled", false);
        map.put("rejectThreshold", 10.0);
        map.put("discardThreshold", 20.0);
        
        RspamdConfig config = new RspamdConfig(map);
        
        assertTrue(config.isEnabled(), "Enabled should be true");
        assertEquals("rspamd.example.com", config.getHost(), "Host should be rspamd.example.com");
        assertEquals(9999, config.getPort(), "Port should be 9999");
        assertEquals(60, config.getTimeout(), "Timeout should be 60");
        assertFalse(config.isSpfScanEnabled(), "SPF scan should be disabled");
        assertFalse(config.isDkimScanEnabled(), "DKIM scan should be disabled");
        assertFalse(config.isDmarcScanEnabled(), "DMARC scan should be disabled");
        assertEquals(10.0, config.getRejectThreshold(), "Reject threshold should be 10.0");
        assertEquals(20.0, config.getDiscardThreshold(), "Discard threshold should be 20.0");
    }

    @Test
    void testPartialConfiguration() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("host", "custom-host");
        // Other values should use defaults
        
        RspamdConfig config = new RspamdConfig(map);
        
        assertTrue(config.isEnabled(), "Enabled should be true");
        assertEquals("custom-host", config.getHost(), "Host should be custom-host");
        assertEquals(11333, config.getPort(), "Port should use default");
        assertEquals(30, config.getTimeout(), "Timeout should use default");
        assertTrue(config.isSpfScanEnabled(), "SPF scan should use default");
        assertTrue(config.isDkimScanEnabled(), "DKIM scan should use default");
        assertTrue(config.isDmarcScanEnabled(), "DMARC scan should use default");
    }

    @Test
    void testEmailAuthenticationFlags() {
        // Test all enabled (default)
        Map<String, Object> map1 = new HashMap<>();
        RspamdConfig config1 = new RspamdConfig(map1);
        assertTrue(config1.isSpfScanEnabled());
        assertTrue(config1.isDkimScanEnabled());
        assertTrue(config1.isDmarcScanEnabled());

        // Test all disabled
        Map<String, Object> map2 = new HashMap<>();
        map2.put("spfScanEnabled", false);
        map2.put("dkimScanEnabled", false);
        map2.put("dmarcScanEnabled", false);
        RspamdConfig config2 = new RspamdConfig(map2);
        assertFalse(config2.isSpfScanEnabled());
        assertFalse(config2.isDkimScanEnabled());
        assertFalse(config2.isDmarcScanEnabled());

        // Test mixed configuration
        Map<String, Object> map3 = new HashMap<>();
        map3.put("spfScanEnabled", true);
        map3.put("dkimScanEnabled", false);
        map3.put("dmarcScanEnabled", true);
        RspamdConfig config3 = new RspamdConfig(map3);
        assertTrue(config3.isSpfScanEnabled());
        assertFalse(config3.isDkimScanEnabled());
        assertTrue(config3.isDmarcScanEnabled());
    }

    @Test
    void testThresholds() {
        Map<String, Object> map = new HashMap<>();
        map.put("rejectThreshold", 5.5);
        map.put("discardThreshold", 12.75);
        
        RspamdConfig config = new RspamdConfig(map);
        
        assertEquals(5.5, config.getRejectThreshold(), 0.001, "Reject threshold should be 5.5");
        assertEquals(12.75, config.getDiscardThreshold(), 0.001, "Discard threshold should be 12.75");
    }

    @Test
    void testEmptyMap() {
        RspamdConfig config = new RspamdConfig(new HashMap<>());
        assertNotNull(config, "Config should not be null");
        assertTrue(config.isEmpty(), "Config map should be empty");
    }

    @Test
    void testNullSafety() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", null);
        map.put("host", null);
        
        RspamdConfig config = new RspamdConfig(map);
        
        // Should use defaults when values are null
        assertFalse(config.isEnabled());
        assertNull(config.getHost()); // String property returns null when value is null
    }
}
