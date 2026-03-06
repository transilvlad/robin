package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for connection whitelist settings.
 * <p>This class provides type-safe access to IP/CIDR whitelist configuration
 * that is used to grant trusted IP addresses a bypass from DoS limits, rate
 * limiting, and RBL checks.
 */
public class WhitelistConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new WhitelistConfig instance.
     *
     * @param map Configuration map.
     */
    public WhitelistConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if connection whitelisting is enabled.
     *
     * @return true if connection whitelisting is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Get the list of trusted IP addresses and CIDR blocks.
     * Supports both IPv4 and IPv6 addresses and CIDR notation.
     *
     * @return List of trusted IP addresses/CIDR blocks.
     */
    @SuppressWarnings("unchecked")
    public List<String> getEntries() {
        if (map.containsKey("entries")) {
            return (List<String>) map.get("entries");
        }
        return Collections.emptyList();
    }
}
