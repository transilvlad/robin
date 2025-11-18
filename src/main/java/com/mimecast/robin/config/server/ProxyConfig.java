package com.mimecast.robin.config.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for proxy settings.
 * <p>This class provides type-safe access to proxy configuration
 * that is used to proxy emails to another SMTP/ESMTP/LMTP server
 * based on regex matching against connection IP addresses and SMTP verb values.
 */
public class ProxyConfig {
    private final Map<String, Object> map;
    private List<ProxyRule> rules;

    /**
     * Constructs a new ProxyConfig instance.
     *
     * @param map Configuration map.
     */
    public ProxyConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
        this.rules = null; // Lazy initialization.
    }

    /**
     * Check if proxy is enabled.
     *
     * @return true if proxy is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Get the list of proxy rules.
     * <p>Rules are parsed from the configuration map on first access
     * and cached for subsequent calls.
     *
     * @return List of ProxyRule instances.
     */
    @SuppressWarnings("unchecked")
    public List<ProxyRule> getRules() {
        if (rules == null) {
            rules = new ArrayList<>();
            if (map.containsKey("rules")) {
                Object rulesObj = map.get("rules");
                if (rulesObj instanceof List) {
                    List<Map<String, Object>> ruleMaps = (List<Map<String, Object>>) rulesObj;
                    for (Map<String, Object> ruleMap : ruleMaps) {
                        // Skip null entries
                        if (ruleMap == null) {
                            continue;
                        }
                        try {
                            rules.add(new ProxyRule(ruleMap));
                        } catch (IllegalArgumentException e) {
                            // Skip invalid rules.
                            // Logging would be done by ProxyRule constructor if needed.
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableList(rules);
    }
}
