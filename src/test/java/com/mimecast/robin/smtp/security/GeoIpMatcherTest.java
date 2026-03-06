package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.GeoIpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeoIpMatcher.
 * <p>Tests that require a real MaxMind database are annotated separately.
 * Country-specific action routing is tested via config alone; database lookups
 * fall back to the default action when no reader is available.
 */
class GeoIpMatcherTest {

    @BeforeEach
    void setUp() {
        // Ensure no stale reader from previous tests.
        GeoIpLookup.setReader(null);
    }

    @AfterEach
    void tearDown() {
        GeoIpLookup.setReader(null);
    }

    private GeoIpConfig disabledConfig() {
        return new GeoIpConfig(null);
    }

    private GeoIpConfig enabledConfig(String defaultAction, Map<String, Object> countries) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("databasePath", "/nonexistent/GeoLite2-Country.mmdb");
        map.put("defaultAction", defaultAction);
        if (countries != null) {
            map.put("countries", countries);
        }
        return new GeoIpConfig(map);
    }

    @Test
    void testDisabledConfigAlwaysAllows() {
        GeoIpConfig config = disabledConfig();
        assertEquals(GeoIpAction.ALLOW, GeoIpMatcher.check("1.2.3.4", config));
        assertEquals(GeoIpAction.ALLOW, GeoIpMatcher.check("185.51.201.1", config));
    }

    @Test
    void testEnabledConfigNoDatabase_DefaultAllow() {
        // No reader injected — lookup returns null — matcher falls back to defaultAction.
        GeoIpConfig config = enabledConfig("allow", null);
        assertEquals(GeoIpAction.ALLOW, GeoIpMatcher.check("1.2.3.4", config));
    }

    @Test
    void testEnabledConfigNoDatabase_DefaultBlock() {
        GeoIpConfig config = enabledConfig("block", null);
        assertEquals(GeoIpAction.BLOCK, GeoIpMatcher.check("1.2.3.4", config));
    }

    @Test
    void testEnabledConfigNoDatabase_DefaultLimit() {
        GeoIpConfig config = enabledConfig("limit", null);
        assertEquals(GeoIpAction.LIMIT, GeoIpMatcher.check("1.2.3.4", config));
    }

    @Test
    void testNullIpFallsBackToDefault() {
        GeoIpConfig config = enabledConfig("allow", null);
        assertEquals(GeoIpAction.ALLOW, GeoIpMatcher.check(null, config));
    }

    @Test
    void testCountryActionsConfigured() {
        Map<String, Object> countries = new HashMap<>();
        countries.put("IR", "block");
        countries.put("RU", "limit");

        GeoIpConfig config = enabledConfig("allow", countries);

        // With no reader, lookup returns null → falls back to defaultAction.
        // The country-specific config routing is exercised when GeoIpLookup returns a code.
        // Verify config correctly reports the actions.
        assertEquals(GeoIpAction.BLOCK, config.getCountryAction("IR"));
        assertEquals(GeoIpAction.LIMIT, config.getCountryAction("RU"));
        assertEquals(GeoIpAction.ALLOW, config.getCountryAction("US"));
    }
}
