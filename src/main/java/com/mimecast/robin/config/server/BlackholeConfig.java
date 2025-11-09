package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for blackhole settings.
 * <p>This class provides type-safe access to blackhole configuration
 * that is used to silently accept but not save emails based on regex
 * matching against connection IP addresses and SMTP verb values.
 */
public class BlackholeConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new BlackholeConfig instance.
     *
     * @param map Configuration map.
     */
    public BlackholeConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if blackhole is enabled.
     *
     * @return true if blackhole is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Get the list of blackhole rule entries.
     * Each entry is a map containing regex patterns for "ip", "ehlo", "mail", and "rcpt".
     *
     * @return List of rule entries.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getRules() {
        if (map.containsKey("rules")) {
            return (List<Map<String, String>>) map.get("rules");
        }
        return Collections.emptyList();
    }
}
