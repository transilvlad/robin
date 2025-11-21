package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bot configuration for email infrastructure analysis bots.
 * <p>This class represents the configuration for automated bots that analyze
 * email infrastructure and reply with diagnostic information.
 * <p>Each bot can be configured with:
 * <ul>
 *   <li>Address patterns using regex to match bot addresses</li>
 *   <li>Domain restrictions to limit which domains can trigger the bot</li>
 *   <li>IP address restrictions to prevent abuse</li>
 *   <li>Bot type/name for factory lookup</li>
 * </ul>
 *
 * <p>Example configuration:
 * <pre>{@code
 * {
 *   "bots": [
 *     {
 *       "addressPattern": "^robot(\\+[^@]+)?@example\\.com$",
 *       "domains": ["example.com"],
 *       "allowedIps": ["127.0.0.1", "::1", "192.168.1.0/24"],
 *       "botName": "session"
 *     }
 *   ]
 * }
 * }</pre>
 */
public class BotConfig extends BasicConfig {

    /**
     * Constructs a new BotConfig instance with null map.
     */
    public BotConfig() {
        super((java.util.Map<String, Object>) null);
    }

    /**
     * Constructs a new BotConfig instance with configuration map.
     *
     * @param map Configuration map.
     */
    public BotConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Gets the list of bot definitions.
     *
     * @return List of bot definition maps.
     */
    @SuppressWarnings("unchecked")
    public List<BotDefinition> getBots() {
        List<BotDefinition> definitions = new ArrayList<>();
        
        // Handle null map gracefully
        if (getMap() == null) {
            return definitions;
        }
        
        List<Map<String, Object>> bots = (List<Map<String, Object>>) getListProperty("bots");
        
        if (bots != null) {
            for (Map<String, Object> botMap : bots) {
                definitions.add(new BotDefinition(botMap));
            }
        }
        
        return definitions;
    }

    /**
     * Represents a single bot definition.
     */
    public static class BotDefinition extends BasicConfig {
        private Pattern compiledPattern;

        /**
         * Constructs a new BotDefinition.
         *
         * @param map Configuration map.
         */
        public BotDefinition(Map<String, Object> map) {
            super(map);
            compilePattern();
        }

        /**
         * Compiles the address pattern regex.
         */
        private void compilePattern() {
            String pattern = getAddressPattern();
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("Invalid regex pattern for bot address: " + pattern, e);
                }
            }
        }

        /**
         * Gets the address pattern regex.
         *
         * @return Address pattern string.
         */
        public String getAddressPattern() {
            return getStringProperty("addressPattern", "");
        }

        /**
         * Gets the compiled pattern.
         *
         * @return Compiled Pattern or null if not set.
         */
        public Pattern getCompiledPattern() {
            return compiledPattern;
        }

        /**
         * Gets the list of allowed domains.
         *
         * @return List of domain strings.
         */
        @SuppressWarnings("unchecked")
        public List<String> getDomains() {
            List<String> domains = (List<String>) getListProperty("domains");
            return domains != null ? domains : new ArrayList<>();
        }

        /**
         * Gets the list of allowed IP addresses or CIDR blocks.
         *
         * @return List of IP address strings.
         */
        @SuppressWarnings("unchecked")
        public List<String> getAllowedIps() {
            List<String> ips = (List<String>) getListProperty("allowedIps");
            return ips != null ? ips : new ArrayList<>();
        }

        /**
         * Gets the bot name for factory lookup.
         *
         * @return Bot name string.
         */
        public String getBotName() {
            return getStringProperty("botName", "");
        }

        /**
         * Checks if the given address matches this bot's pattern.
         *
         * @param address Email address to check.
         * @return true if address matches pattern.
         */
        public boolean matchesAddress(String address) {
            if (compiledPattern == null || address == null || address.isEmpty()) {
                return false;
            }
            return compiledPattern.matcher(address).matches();
        }

        /**
         * Checks if the given domain is in the allowed domains list.
         * <p>If no domains are configured, all domains are allowed.
         *
         * @param domain Domain to check.
         * @return true if domain is allowed.
         */
        public boolean isDomainAllowed(String domain) {
            List<String> allowedDomains = getDomains();
            if (allowedDomains.isEmpty()) {
                return true; // No domain restriction
            }
            if (domain == null || domain.isEmpty()) {
                return false;
            }
            return allowedDomains.stream()
                    .anyMatch(allowed -> domain.equalsIgnoreCase(allowed));
        }

        /**
         * Checks if the given IP address is in the allowed IPs list.
         * <p>If no IPs are configured, all IPs are allowed.
         * <p>Supports both individual IPs and CIDR notation.
         *
         * @param ipAddress IP address to check.
         * @return true if IP is allowed.
         */
        public boolean isIpAllowed(String ipAddress) {
            List<String> allowedIps = getAllowedIps();
            if (allowedIps.isEmpty()) {
                return true; // No IP restriction
            }
            if (ipAddress == null || ipAddress.isEmpty()) {
                return false;
            }
            
            // Simple implementation - exact match or CIDR prefix match
            // For a production system, consider using a proper CIDR library
            for (String allowed : allowedIps) {
                if (allowed.equalsIgnoreCase(ipAddress)) {
                    return true;
                }
                // Basic CIDR check - just prefix matching for simplicity
                if (allowed.contains("/")) {
                    String prefix = allowed.substring(0, allowed.indexOf("/"));
                    if (ipAddress.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            
            return false;
        }
    }
}
