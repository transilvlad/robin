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
 *   <li>IP address restrictions to prevent abuse (allowedIps)</li>
 *   <li>Token-based authentication as alternative to IP restrictions (allowedTokens)</li>
 *   <li>Bot type/name for factory lookup</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> Bots are authorized if either:
 * <ul>
 *   <li>The sender IP is in allowedIps list (or allowedIps is empty), OR</li>
 *   <li>The bot address contains a token from allowedTokens list (or allowedTokens is empty)</li>
 * </ul>
 * <p>If both lists are empty, all requests are allowed (use with caution).
 *
 * <p>Example configuration:
 * <pre>{@code
 * {
 *   "bots": [
 *     {
 *       "addressPattern": "^robot(\\+[^@]+)?@example\\.com$",
 *       "allowedIps": ["127.0.0.1", "::1", "192.168.1.0/24"],
 *       "allowedTokens": ["secret123", "token456"],
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
        
        // Handle null map gracefully.
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
         * Gets the list of allowed IP addresses or CIDR blocks.
         * <p>Used for IP-based authorization. If empty, IP check is skipped.
         *
         * @return List of IP address strings.
         */
        @SuppressWarnings("unchecked")
        public List<String> getAllowedIps() {
            List<String> ips = (List<String>) getListProperty("allowedIps");
            return ips != null ? ips : new ArrayList<>();
        }

        /**
         * Gets the list of allowed tokens for authentication.
         * <p>Used for token-based authorization. Tokens are extracted from bot addresses
         * <br>like: robotSession+token@example.com
         * <p>If empty, token check is skipped.
         *
         * @return List of token strings.
         */
        @SuppressWarnings("unchecked")
        public List<String> getAllowedTokens() {
            List<String> tokens = (List<String>) getListProperty("allowedTokens");
            return tokens != null ? tokens : new ArrayList<>();
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
         * Checks if the bot request is authorized based on IP or token.
         * <p>Authorization logic:
         * <ul>
         *   <li>If both allowedIps and allowedTokens are empty: allow all (NOT recommended for production)</li>
         *   <li>If only allowedIps is populated: must match IP</li>
         *   <li>If only allowedTokens is populated: must match token</li>
         *   <li>If both are populated: must match IP OR token (either is sufficient)</li>
         * </ul>
         *
         * @param address Bot address to check for token.
         * @param ipAddress IP address to check.
         * @return true if authorized by IP or token.
         */
        public boolean isAuthorized(String address, String ipAddress) {
            List<String> allowedIps = getAllowedIps();
            List<String> allowedTokens = getAllowedTokens();

            boolean hasIpRestriction = !allowedIps.isEmpty();
            boolean hasTokenRestriction = !allowedTokens.isEmpty();

            // If neither restriction is configured, allow all (use with caution).
            if (!hasIpRestriction && !hasTokenRestriction) {
                return true;
            }

            // Check IP authorization (if IP restriction is configured).
            boolean ipAuthorized = false;
            if (hasIpRestriction) {
                ipAuthorized = isIpAllowedInternal(ipAddress, allowedIps);
            }

            // Check token authorization (if token restriction is configured).
            boolean tokenAuthorized = false;
            if (hasTokenRestriction) {
                tokenAuthorized = hasValidTokenInternal(address, allowedTokens);
            }

            // If both restrictions are configured, either can authorize.
            if (hasIpRestriction && hasTokenRestriction) {
                return ipAuthorized || tokenAuthorized;
            }

            // If only IP restriction is configured, must pass IP check.
            if (hasIpRestriction) {
                return ipAuthorized;
            }

            // If only token restriction is configured, must pass token check.
            return tokenAuthorized;
        }

        /**
         * Internal method to check if IP is allowed.
         *
         * @param ipAddress IP address to check.
         * @param allowedIps List of allowed IPs.
         * @return true if IP is allowed or list is empty.
         */
        private boolean isIpAllowedInternal(String ipAddress, List<String> allowedIps) {
            if (allowedIps.isEmpty()) {
                return true; // No IP restriction.
            }
            if (ipAddress == null || ipAddress.isEmpty()) {
                return false;
            }

            // Simple implementation - exact match or CIDR prefix match.
            // For a production system, consider using a proper CIDR library.
            for (String allowed : allowedIps) {
                if (allowed.equalsIgnoreCase(ipAddress)) {
                    return true;
                }
                // Basic CIDR check - extract network prefix (without host portion).
                if (allowed.contains("/")) {
                    String cidrPrefix = allowed.substring(0, allowed.indexOf("/"));
                    // Remove trailing .0 or similar host portion to get network prefix.
                    // For example: 192.168.1.0/24 -> 192.168.1.
                    // This handles common cases like /24, /16, /8.
                    int lastDot = cidrPrefix.lastIndexOf('.');
                    if (lastDot > 0) {
                        String networkPrefix = cidrPrefix.substring(0, lastDot);
                        if (ipAddress.startsWith(networkPrefix + ".")) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        /**
         * Internal method to check if address contains valid token.
         *
         * @param address Bot address to check.
         * @param allowedTokens List of allowed tokens.
         * @return true if token is valid or list is empty.
         */
        private boolean hasValidTokenInternal(String address, List<String> allowedTokens) {
            if (allowedTokens.isEmpty()) {
                return true; // No token restriction.
            }
            if (address == null || address.isEmpty()) {
                return false;
            }

            // Extract token from address (format: prefix+token@domain or prefix+token+reply+...@domain).
            int plusIndex = address.indexOf('+');
            int atIndex = address.indexOf('@');
            if (plusIndex != -1 && atIndex != -1 && plusIndex < atIndex) {
                String tokenPart = address.substring(plusIndex + 1, atIndex);
                // Handle formats like token+reply+...
                int nextPlusIndex = tokenPart.indexOf('+');
                if (nextPlusIndex != -1) {
                    tokenPart = tokenPart.substring(0, nextPlusIndex);
                }

                // Check if extracted token matches any configured token.
                final String extractedToken = tokenPart;
                return allowedTokens.stream().anyMatch(token -> token.equals(extractedToken));
            }

            return false;
        }
    }
}
