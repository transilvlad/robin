package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for connection blocklist settings.
 * <p>This class provides type-safe access to IP/CIDR blocklist configuration
 * that is used to immediately reject connections from specific IP addresses or networks.
 */
public class BlocklistConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new BlocklistConfig instance.
     *
     * @param map Configuration map.
     */
    public BlocklistConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if connection blocking is enabled.
     *
     * @return true if connection blocking is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Get the list of IP addresses and CIDR blocks to block.
     * Supports both IPv4 and IPv6 addresses and CIDR notation.
     *
     * @return List of IP addresses/CIDR blocks to block.
     */
    @SuppressWarnings("unchecked")
    public List<String> getEntries() {
        if (map.containsKey("entries")) {
            return (List<String>) map.get("entries");
        }
        return Collections.emptyList();
    }
}
