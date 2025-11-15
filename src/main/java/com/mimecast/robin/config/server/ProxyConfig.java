package com.mimecast.robin.config.server;

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

    /**
     * Constructs a new ProxyConfig instance.
     *
     * @param map Configuration map.
     */
    public ProxyConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
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
     * Get the list of proxy rule entries.
     * Each entry is a map containing:
     * - Regex patterns for "ip", "ehlo", "mail", and "rcpt" (like blackhole)
     * - Relay destination: "host", "port", "protocol", "tls"
     * - Action for non-matching recipients: "action" (accept, reject, none)
     *
     * @return List of rule entries.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRules() {
        if (map.containsKey("rules")) {
            return (List<Map<String, Object>>) map.get("rules");
        }
        return Collections.emptyList();
    }
}
