package com.mimecast.robin.config.server;

import com.mimecast.robin.smtp.security.GeoIpAction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeoIpConfig.
 */
class GeoIpConfigTest {

    @Test
    void testDefaultConfigDisabled() {
        GeoIpConfig config = new GeoIpConfig(null);
        assertFalse(config.isEnabled(), "Default config should be disabled");
        assertEquals("/usr/local/robin/GeoLite2-Country.mmdb", config.getDatabasePath());
        assertEquals(GeoIpAction.ALLOW, config.getDefaultAction());
    }

    @Test
    void testEnabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("databasePath", "/data/GeoLite2-Country.mmdb");
        map.put("defaultAction", "allow");

        GeoIpConfig config = new GeoIpConfig(map);
        assertTrue(config.isEnabled());
        assertEquals("/data/GeoLite2-Country.mmdb", config.getDatabasePath());
        assertEquals(GeoIpAction.ALLOW, config.getDefaultAction());
    }

    @Test
    void testDefaultActionBlock() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "block");

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.BLOCK, config.getDefaultAction());
    }

    @Test
    void testDefaultActionLimit() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "limit");

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.LIMIT, config.getDefaultAction());
    }

    @Test
    void testCountryActionBlock() {
        Map<String, Object> countries = new HashMap<>();
        countries.put("IR", "block");
        countries.put("RU", "limit");

        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "allow");
        map.put("countries", countries);

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.BLOCK, config.getCountryAction("IR"), "IR should be blocked");
        assertEquals(GeoIpAction.LIMIT, config.getCountryAction("RU"), "RU should be limited");
        assertEquals(GeoIpAction.ALLOW, config.getCountryAction("US"), "US should use default action");
    }

    @Test
    void testCountryCodeCaseInsensitive() {
        Map<String, Object> countries = new HashMap<>();
        countries.put("IR", "block");

        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "allow");
        map.put("countries", countries);

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.BLOCK, config.getCountryAction("ir"), "Lowercase should still match");
        assertEquals(GeoIpAction.BLOCK, config.getCountryAction("Ir"), "Mixed case should still match");
    }

    @Test
    void testNullCountryCodeFallsBackToDefault() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "block");

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.BLOCK, config.getCountryAction(null), "Null country should use default action");
    }

    @Test
    void testInvalidDefaultActionFallsBackToAllow() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("defaultAction", "invalid");

        GeoIpConfig config = new GeoIpConfig(map);
        assertEquals(GeoIpAction.ALLOW, config.getDefaultAction(), "Invalid action should fall back to ALLOW");
    }
}
