package com.mimecast.robin.config.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for RBL (Realtime Blackhole List) settings.
 */
public class RblConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new RblConfig instance.
     *
     * @param map Configuration map.
     */
    public RblConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if RBL checking is enabled.
     *
     * @return true if RBL checking is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Get the list of RBL providers to check against.
     *
     * @return List of RBL provider domains.
     */
    @SuppressWarnings("unchecked")
    public List<String> getProviders() {
        if (map.containsKey("providers")) {
            return (List<String>) map.get("providers");
        }
        // Default RBL providers.
        return Arrays.asList("zen.spamhaus.org", "bl.spamcop.net");
    }

    /**
     * Get the timeout in seconds for RBL queries.
     *
     * @return Timeout in seconds.
     */
    public int getTimeoutSeconds() {
        return map.containsKey("timeoutSeconds") ?
            ((Number) map.get("timeoutSeconds")).intValue() : 5;
    }
}
