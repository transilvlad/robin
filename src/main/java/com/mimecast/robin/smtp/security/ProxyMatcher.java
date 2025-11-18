package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.ProxyConfig;
import com.mimecast.robin.config.server.ProxyRule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
     * <p>If multiple rules match, only the first match is returned and warnings are logged
     * for subsequent matches.
     *
     * @param ip        The IP address (can be null).
     * @param ehlo      The EHLO/HELO domain (can be null).
     * @param mail      The MAIL FROM address (can be null).
     * @param rcpt      The RCPT TO address (can be null).
     * @param isInbound true if the session is inbound, false if outbound.
     * @param config    The proxy configuration.
     * @return Optional containing the first matching rule, or empty if no match.
     */
    public static Optional<ProxyRule> findMatchingRule(String ip, String ehlo, String mail, String rcpt,
                                                        boolean isInbound, ProxyConfig config) {
        // If proxy is not enabled, don't proxy anything.
        if (!config.isEnabled()) {
            return Optional.empty();
        }

        List<ProxyRule> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }

        // Track all matching rules to log warnings for additional matches.
        List<ProxyRule> matchingRules = new ArrayList<>();

        // Check each rule and collect all matches.
        for (ProxyRule rule : rules) {
            if (matchesRule(ip, ehlo, mail, rcpt, isInbound, rule)) {
                matchingRules.add(rule);
            }
        }

        // If we have matches, return the first and log warnings for others.
        if (!matchingRules.isEmpty()) {
            ProxyRule firstMatch = matchingRules.get(0);
            log.info("Proxy match - IP: {}, EHLO: {}, MAIL: {}, RCPT: {}, Host: {}, Direction: {}",
                ip, ehlo, mail, rcpt, firstMatch.getHost(), isInbound ? "inbound" : "outbound");

            // Warn about additional matches that will be ignored.
            if (matchingRules.size() > 1) {
                for (int i = 1; i < matchingRules.size(); i++) {
                    ProxyRule ignored = matchingRules.get(i);
                    log.warn("Additional proxy rule match ignored (only first match is used) - Host: {}, Port: {}",
                        ignored.getHost(), ignored.getPort());
                }
            }

            return Optional.of(firstMatch);
        }

        return Optional.empty();
    }

    /**
     * Checks if the provided values match a single rule.
     * All specified patterns in the rule must match for the rule to match.
     *
     * @param ip        The IP address.
     * @param ehlo      The EHLO/HELO domain.
     * @param mail      The MAIL FROM address.
     * @param rcpt      The RCPT TO address.
     * @param isInbound true if the session is inbound, false if outbound.
     * @param rule      The rule to match against.
     * @return true if all patterns in the rule match, false otherwise.
     */
    private static boolean matchesRule(String ip, String ehlo, String mail, String rcpt, boolean isInbound, ProxyRule rule) {
        // Check direction first.
        if (!rule.matchesDirection(isInbound)) {
            return false;
        }

        // Check IP pattern if specified.
        if (!matchesPattern(ip, rule.getIp())) {
            return false;
        }

        // Check EHLO pattern if specified.
        if (!matchesPattern(ehlo, rule.getEhlo())) {
            return false;
        }

        // Check MAIL pattern if specified.
        if (!matchesPattern(mail, rule.getMail())) {
            return false;
        }

        // Check RCPT pattern if specified.
        if (!matchesPattern(rcpt, rule.getRcpt())) {
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
}
