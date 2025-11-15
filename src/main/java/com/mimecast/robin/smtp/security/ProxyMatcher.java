package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.ProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility class for matching emails against proxy rules.
 * <p>Supports regex matching for IP addresses and SMTP verb values (EHLO, MAIL, RCPT).
 * <p>This class is thread-safe and designed to work with config auto-reload.
 */
public class ProxyMatcher {
    private static final Logger log = LogManager.getLogger(ProxyMatcher.class);

    /**
     * Finds the first matching proxy rule for the given connection/envelope.
     * <p>This method creates a new matcher instance on each call to support config auto-reload.
     *
     * @param ip     The IP address (can be null).
     * @param ehlo   The EHLO/HELO domain (can be null).
     * @param mail   The MAIL FROM address (can be null).
     * @param rcpt   The RCPT TO address (can be null).
     * @param config The proxy configuration.
     * @return Optional containing the first matching rule, or empty if no match.
     */
    public static Optional<Map<String, Object>> findMatchingRule(String ip, String ehlo, String mail, String rcpt, ProxyConfig config) {
        // If proxy is not enabled, don't proxy anything.
        if (!config.isEnabled()) {
            return Optional.empty();
        }

        List<Map<String, Object>> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }

        // Check each rule and return the first match.
        for (Map<String, Object> rule : rules) {
            if (matchesRule(ip, ehlo, mail, rcpt, rule)) {
                log.info("Proxy match - IP: {}, EHLO: {}, MAIL: {}, RCPT: {}", ip, ehlo, mail, rcpt);
                return Optional.of(rule);
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if the provided values match a single rule.
     * All specified patterns in the rule must match for the rule to match.
     *
     * @param ip   The IP address.
     * @param ehlo The EHLO/HELO domain.
     * @param mail The MAIL FROM address.
     * @param rcpt The RCPT TO address.
     * @param rule The rule to match against.
     * @return true if all patterns in the rule match, false otherwise.
     */
    private static boolean matchesRule(String ip, String ehlo, String mail, String rcpt, Map<String, Object> rule) {
        // Check IP pattern if specified.
        if (rule.containsKey("ip") && !matchesPattern(ip, (String) rule.get("ip"))) {
            return false;
        }

        // Check EHLO pattern if specified.
        if (rule.containsKey("ehlo") && !matchesPattern(ehlo, (String) rule.get("ehlo"))) {
            return false;
        }

        // Check MAIL pattern if specified.
        if (rule.containsKey("mail") && !matchesPattern(mail, (String) rule.get("mail"))) {
            return false;
        }

        // Check RCPT pattern if specified.
        if (rule.containsKey("rcpt") && !matchesPattern(rcpt, (String) rule.get("rcpt"))) {
            return false;
        }

        // All specified patterns matched.
        return true;
    }

    /**
     * Checks if a value matches a regex pattern.
     *
     * @param value   The value to check (can be null).
     * @param pattern The regex pattern to match against.
     * @return true if the value matches the pattern, false otherwise.
     */
    private static boolean matchesPattern(String value, String pattern) {
        // If pattern is null or empty, it means no restriction - match anything.
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        
        // If we have a pattern but value is null, no match.
        if (value == null) {
            return false;
        }

        try {
            return Pattern.matches(pattern, value);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern: {}", pattern, e);
            return false;
        }
    }

    /**
     * Gets the action for non-matching recipients from a proxy rule.
     *
     * @param rule The proxy rule.
     * @return The action string (accept, reject, or none). Default is "none".
     */
    public static String getAction(Map<String, Object> rule) {
        if (rule.containsKey("action")) {
            String action = (String) rule.get("action");
            if ("accept".equalsIgnoreCase(action) || "reject".equalsIgnoreCase(action) || "none".equalsIgnoreCase(action)) {
                return action.toLowerCase();
            }
        }
        return "none";
    }

    /**
     * Gets the host from a proxy rule.
     *
     * @param rule The proxy rule.
     * @return The host string, or "localhost" if not specified.
     */
    public static String getHost(Map<String, Object> rule) {
        return rule.containsKey("host") ? (String) rule.get("host") : "localhost";
    }

    /**
     * Gets the port from a proxy rule.
     *
     * @param rule The proxy rule.
     * @return The port number, or 25 if not specified.
     */
    public static int getPort(Map<String, Object> rule) {
        if (rule.containsKey("port")) {
            Object port = rule.get("port");
            if (port instanceof Number) {
                return ((Number) port).intValue();
            } else if (port instanceof String) {
                try {
                    return Integer.parseInt((String) port);
                } catch (NumberFormatException e) {
                    log.warn("Invalid port number: {}", port);
                }
            }
        }
        return 25;
    }

    /**
     * Gets the protocol from a proxy rule.
     *
     * @param rule The proxy rule.
     * @return The protocol string (smtp, esmtp, lmtp), or "esmtp" if not specified.
     */
    public static String getProtocol(Map<String, Object> rule) {
        if (rule.containsKey("protocol")) {
            String protocol = (String) rule.get("protocol");
            if (protocol != null && !protocol.isEmpty()) {
                return protocol.toLowerCase();
            }
        }
        return "esmtp";
    }

    /**
     * Gets the TLS setting from a proxy rule.
     *
     * @param rule The proxy rule.
     * @return true if TLS should be used, false otherwise.
     */
    public static boolean isTls(Map<String, Object> rule) {
        if (rule.containsKey("tls")) {
            Object tls = rule.get("tls");
            if (tls instanceof Boolean) {
                return (Boolean) tls;
            } else if (tls instanceof String) {
                return Boolean.parseBoolean((String) tls);
            }
        }
        return false;
    }
}
