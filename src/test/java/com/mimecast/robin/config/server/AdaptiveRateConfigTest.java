package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AdaptiveRateConfig.
 */
class AdaptiveRateConfigTest {

    @Test
    void testDefaultConfigDisabled() {
        AdaptiveRateConfig config = new AdaptiveRateConfig(null);
        assertFalse(config.isEnabled(), "Default config should be disabled");
        assertEquals(0.8, config.getHighThreshold(), 0.001, "Default high threshold should be 0.8");
        assertEquals(0.3, config.getLowThreshold(), 0.001, "Default low threshold should be 0.3");
        assertEquals(0.5, config.getReductionFactor(), 0.001, "Default reduction factor should be 0.5");
    }

    @Test
    void testEnabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("highThreshold", 0.9);
        map.put("lowThreshold", 0.4);
        map.put("reductionFactor", 0.6);

        AdaptiveRateConfig config = new AdaptiveRateConfig(map);
        assertTrue(config.isEnabled(), "Config should be enabled");
        assertEquals(0.9, config.getHighThreshold(), 0.001);
        assertEquals(0.4, config.getLowThreshold(), 0.001);
        assertEquals(0.6, config.getReductionFactor(), 0.001);
    }

    @Test
    void testDisabledConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", false);

        AdaptiveRateConfig config = new AdaptiveRateConfig(map);
        assertFalse(config.isEnabled(), "Config should be disabled");
    }

    @Test
    void testMissingEnabledKey() {
        Map<String, Object> map = new HashMap<>();
        map.put("highThreshold", 0.75);

        AdaptiveRateConfig config = new AdaptiveRateConfig(map);
        assertFalse(config.isEnabled(), "Config without enabled key should be disabled");
        assertEquals(0.75, config.getHighThreshold(), 0.001, "Custom high threshold should be respected");
    }

    @Test
    void testIntegerThresholds() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("highThreshold", 1);
        map.put("lowThreshold", 0);
        map.put("reductionFactor", 1);

        AdaptiveRateConfig config = new AdaptiveRateConfig(map);
        assertEquals(1.0, config.getHighThreshold(), 0.001);
        assertEquals(0.0, config.getLowThreshold(), 0.001);
        assertEquals(1.0, config.getReductionFactor(), 0.001);
    }
}
