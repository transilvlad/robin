package com.mimecast.robin.config.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a single proxy rule configuration.
 * <p>This class provides type-safe access to proxy rule settings including
 * matching patterns (IP, EHLO, MAIL, RCPT), destination configuration,
 * authentication credentials, and direction filtering.
 */
public class ProxyRule {
    private final String ip;
    private final String ehlo;
    private final String mail;
    private final String rcpt;
    private final List<String> hosts;
    private final int port;
    private final String protocol;
    private final boolean tls;
    private final String action;
    private final String direction;
    private final String authUsername;
    private final String authPassword;
    private final String authMechanism;

    /**
     * Action constants for non-matching recipients.
     */
    public static final String ACTION_NONE = "none";
    public static final String ACTION_ACCEPT = "accept";
    public static final String ACTION_REJECT = "reject";

    /**
     * Direction constants for filtering.
     */
    public static final String DIRECTION_BOTH = "both";
    public static final String DIRECTION_INBOUND = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";

    /**
     * Default values.
     */
    public static final int DEFAULT_PORT = 25;
    public static final String DEFAULT_PROTOCOL = "esmtp";
    public static final String DEFAULT_ACTION = ACTION_NONE;
    public static final String DEFAULT_DIRECTION = DIRECTION_BOTH;

    /**
     * Constructs a new ProxyRule from a configuration map.
     *
     * @param map Configuration map containing rule settings.
     */
    public ProxyRule(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("ProxyRule configuration map cannot be null");
        }

        // Matching patterns (optional).
        this.ip = getStringValue(map, "ip");
        this.ehlo = getStringValue(map, "ehlo");
        this.mail = getStringValue(map, "mail");
        this.rcpt = getStringValue(map, "rcpt");

        // Destination configuration - hosts is now a list.
        this.hosts = getListValue(map, "hosts");
        if (this.hosts.isEmpty()) {
            throw new IllegalArgumentException("ProxyRule must specify at least one host in 'hosts' list");
        }

        this.port = getIntValue(map, "port", DEFAULT_PORT);
        this.protocol = getStringValue(map, "protocol", DEFAULT_PROTOCOL).toLowerCase();
        this.tls = getBooleanValue(map, "tls", false);

        // Action for non-matching recipients.
        String actionValue = getStringValue(map, "action", DEFAULT_ACTION).toLowerCase();
        if (!ACTION_ACCEPT.equals(actionValue) && !ACTION_REJECT.equals(actionValue) && !ACTION_NONE.equals(actionValue)) {
            this.action = DEFAULT_ACTION;
        } else {
            this.action = actionValue;
        }

        // Direction filtering (both, inbound, outbound).
        String directionValue = getStringValue(map, "direction", DEFAULT_DIRECTION).toLowerCase();
        if (!DIRECTION_BOTH.equals(directionValue) && !DIRECTION_INBOUND.equals(directionValue) && !DIRECTION_OUTBOUND.equals(directionValue)) {
            this.direction = DEFAULT_DIRECTION;
        } else {
            this.direction = directionValue;
        }

        // Authentication configuration (optional).
        this.authUsername = getStringValue(map, "authUsername");
        this.authPassword = getStringValue(map, "authPassword");
        this.authMechanism = getStringValue(map, "authMechanism", "PLAIN");
    }

    /**
     * Gets list value from map.
     *
     * @param map Configuration map.
     * @param key Key to retrieve.
     * @return List of strings, or empty list if not present.
     */
    @SuppressWarnings("unchecked")
    private List<String> getListValue(Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            Object value = map.get(key);
            if (value instanceof List) {
                List<String> result = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    if (item != null) {
                        result.add(item.toString());
                    }
                }
                return result;
            } else if (value != null) {
                // Single value, convert to list.
                return Collections.singletonList(value.toString());
            }
        }
        return Collections.emptyList();
    }

    /**
     * Gets string value from map.
     *
     * @param map Configuration map.
     * @param key Key to retrieve.
     * @return String value or null if not present.
     */
    private String getStringValue(Map<String, Object> map, String key) {
        return getStringValue(map, key, null);
    }

    /**
     * Gets string value from map with default.
     *
     * @param map          Configuration map.
     * @param key          Key to retrieve.
     * @param defaultValue Default value if not present.
     * @return String value or default.
     */
    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        if (map.containsKey(key) && map.get(key) != null) {
            return map.get(key).toString();
        }
        return defaultValue;
    }

    /**
     * Gets integer value from map with default.
     *
     * @param map          Configuration map.
     * @param key          Key to retrieve.
     * @param defaultValue Default value if not present.
     * @return Integer value or default.
     */
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        if (map.containsKey(key)) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Gets boolean value from map with default.
     *
     * @param map          Configuration map.
     * @param key          Key to retrieve.
     * @param defaultValue Default value if not present.
     * @return Boolean value or default.
     */
    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        if (map.containsKey(key)) {
            Object value = map.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            } else if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
        }
        return defaultValue;
    }

    /**
     * Gets IP address pattern.
     *
     * @return IP pattern regex or null if not specified.
     */
    public String getIp() {
        return ip;
    }

    /**
     * Gets EHLO/HELO domain pattern.
     *
     * @return EHLO pattern regex or null if not specified.
     */
    public String getEhlo() {
        return ehlo;
    }

    /**
     * Gets MAIL FROM pattern.
     *
     * @return MAIL pattern regex or null if not specified.
     */
    public String getMail() {
        return mail;
    }

    /**
     * Gets RCPT TO pattern.
     *
     * @return RCPT pattern regex or null if not specified.
     */
    public String getRcpt() {
        return rcpt;
    }

    /**
     * Gets destination hosts.
     *
     * @return Unmodifiable list of host strings.
     */
    public List<String> getHosts() {
        return Collections.unmodifiableList(hosts);
    }

    /**
     * Gets the first host from the hosts list.
     * <p>This is a convenience method for simple configurations.
     *
     * @return First host string.
     */
    public String getHost() {
        return hosts.isEmpty() ? "localhost" : hosts.get(0);
    }

    /**
     * Gets destination port.
     *
     * @return Port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets protocol (smtp, esmtp, lmtp).
     *
     * @return Protocol string.
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Checks if TLS should be used.
     *
     * @return true if TLS enabled, false otherwise.
     */
    public boolean isTls() {
        return tls;
    }

    /**
     * Gets action for non-matching recipients.
     *
     * @return Action string (accept, reject, or none).
     */
    public String getAction() {
        return action;
    }

    /**
     * Gets direction filter.
     *
     * @return Direction string (both, inbound, or outbound).
     */
    public String getDirection() {
        return direction;
    }

    /**
     * Checks if this rule matches the given direction.
     *
     * @param isInbound true if the session is inbound, false if outbound.
     * @return true if the rule matches the direction, false otherwise.
     */
    public boolean matchesDirection(boolean isInbound) {
        if (DIRECTION_BOTH.equals(direction)) {
            return true;
        }
        if (DIRECTION_INBOUND.equals(direction)) {
            return isInbound;
        }
        if (DIRECTION_OUTBOUND.equals(direction)) {
            return !isInbound;
        }
        return true; // Default to both if invalid value.
    }

    /**
     * Gets authentication username.
     *
     * @return Username or null if not specified.
     */
    public String getAuthUsername() {
        return authUsername;
    }

    /**
     * Gets authentication password.
     *
     * @return Password or null if not specified.
     */
    public String getAuthPassword() {
        return authPassword;
    }

    /**
     * Gets authentication mechanism.
     *
     * @return Auth mechanism (default: PLAIN).
     */
    public String getAuthMechanism() {
        return authMechanism;
    }

    /**
     * Checks if authentication is configured.
     *
     * @return true if both username and password are set, false otherwise.
     */
    public boolean hasAuth() {
        return authUsername != null && !authUsername.isEmpty()
            && authPassword != null && !authPassword.isEmpty();
    }

    /**
     * Generates a hash code for this ProxyRule.
     * <p>Used for connection reuse - rules with the same destination configuration
     * will generate the same hash code, allowing connection pooling.
     *
     * @return Hash code based on destination configuration.
     */
    @Override
    public int hashCode() {
        int result = hosts.hashCode();
        result = 31 * result + port;
        result = 31 * result + protocol.hashCode();
        result = 31 * result + (tls ? 1 : 0);
        result = 31 * result + (authUsername != null ? authUsername.hashCode() : 0);
        return result;
    }

    /**
     * Checks if this ProxyRule equals another object.
     * <p>Two ProxyRules are equal if they have the same destination configuration,
     * allowing connection reuse for identical proxy destinations.
     *
     * @param obj Object to compare.
     * @return true if the rules have the same destination configuration.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ProxyRule that = (ProxyRule) obj;

        if (port != that.port) return false;
        if (tls != that.tls) return false;
        if (!hosts.equals(that.hosts)) return false;
        if (!protocol.equals(that.protocol)) return false;
        if (authUsername != null ? !authUsername.equals(that.authUsername) : that.authUsername != null)
            return false;
        return authPassword != null ? authPassword.equals(that.authPassword) : that.authPassword == null;
    }
}
