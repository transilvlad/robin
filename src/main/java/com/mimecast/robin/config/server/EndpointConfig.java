package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Endpoint authentication configuration.
 *
 * <p>This class provides type safe access to endpoint authentication configuration
 * for metrics and API endpoints. It supports both basic and bearer authentication
 * types similar to webhook authentication.
 *
 * <p>IP allow lists can be configured to bypass authentication for specific addresses
 * or CIDR blocks.
 */
@SuppressWarnings("unchecked")
public class EndpointConfig extends BasicConfig {

    /**
     * Constructs a new EndpointConfig instance with given map.
     *
     * @param map Configuration map.
     */
    public EndpointConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Gets the port number for this endpoint.
     *
     * @param defaultPort Default port to use if not configured.
     * @return Port number.
     */
    public int getPort(int defaultPort) {
        return Math.toIntExact(getLongProperty("port", (long) defaultPort));
    }

    /**
     * Gets authentication type (none, basic, bearer).
     *
     * @return Auth type string.
     */
    public String getAuthType() {
        return getStringProperty("authType", "basic");
    }

    /**
     * Gets authentication value (username:password for basic, token for bearer).
     *
     * @return Auth value string.
     */
    public String getAuthValue() {
        return getStringProperty("authValue", "");
    }

    /**
     * Gets IP addresses or CIDR blocks that are allowed without authentication.
     *
     * @return List of IP addresses or CIDR blocks.
     */
    public List<String> getAllowList() {
        if (map.containsKey("allowList")) {
            Object value = map.get("allowList");
            if (value instanceof List) {
                return (List<String>) value;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Checks if authentication is enabled.
     *
     * @return True if authentication is enabled, false otherwise.
     */
    public boolean isAuthEnabled() {
        String authType = getAuthType();
        String authValue = getAuthValue();
        return authType != null && !authType.equalsIgnoreCase("none")
                && authValue != null && !authValue.isEmpty();
    }
}
