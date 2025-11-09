package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.BlackholeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility class for matching emails against blackhole rules.
 * <p>Supports regex matching for IP addresses and SMTP verb values (EHLO, MAIL, RCPT).
 * <p>This class is thread-safe and designed to work with config auto-reload.
 */
public class BlackholeMatcher {
    private static final Logger log = LogManager.getLogger(BlackholeMatcher.class);

    /**
     * Checks if the connection/envelope should be blackholed based on the rules.
     * <p>This method creates a new matcher instance on each call to support config auto-reload.
     *
     * @param ip     The IP address (can be null).
     * @param ehlo   The EHLO/HELO domain (can be null).
     * @param mail   The MAIL FROM address (can be null).
     * @param rcpt   The RCPT TO address (can be null).
     * @param config The blackhole configuration.
     * @return true if the email should be blackholed, false otherwise.
     */
    public static boolean shouldBlackhole(String ip, String ehlo, String mail, String rcpt, BlackholeConfig config) {
        // If blackhole is not enabled, don't blackhole anything
        if (!config.isEnabled()) {
            return false;
        }

        List<Map<String, String>> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        // Check each rule
        for (Map<String, String> rule : rules) {
            if (matchesRule(ip, ehlo, mail, rcpt, rule)) {
                log.info("Blackhole match - IP: {}, EHLO: {}, MAIL: {}, RCPT: {}", ip, ehlo, mail, rcpt);
                return true;
            }
        }

        return false;
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
    private static boolean matchesRule(String ip, String ehlo, String mail, String rcpt, Map<String, String> rule) {
        // Check IP pattern if specified
        if (rule.containsKey("ip") && !matchesPattern(ip, rule.get("ip"))) {
            return false;
        }

        // Check EHLO pattern if specified
        if (rule.containsKey("ehlo") && !matchesPattern(ehlo, rule.get("ehlo"))) {
            return false;
        }

        // Check MAIL pattern if specified
        if (rule.containsKey("mail") && !matchesPattern(mail, rule.get("mail"))) {
            return false;
        }

        // Check RCPT pattern if specified
        if (rule.containsKey("rcpt") && !matchesPattern(rcpt, rule.get("rcpt"))) {
            return false;
        }

        // All specified patterns matched
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
        // If pattern is null or empty, it means no restriction - match anything
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        
        // If we have a pattern but value is null, no match
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
}
