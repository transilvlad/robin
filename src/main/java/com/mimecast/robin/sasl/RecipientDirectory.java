package com.mimecast.robin.sasl;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves managed recipients atomically before acceptance. Routing edges never
 * authorize send-as; disabled managed domains cannot become external forwards.
 */
public final class RecipientDirectory {
    public static final String DOMAIN_QUERY =
            "SELECT enabled,plus_addressing,catch_all FROM mail_domains WHERE domain=?";
    public static final String ALIAS_QUERY =
            "SELECT destination FROM aliases WHERE lower(source)=?";
    public static final String MAILBOX_QUERY =
            "SELECT 1 FROM users WHERE lower(email)=?";

    public record DomainPolicy(boolean enabled, boolean plusAddressing, String catchAll) {}

    public interface Lookup {
        Optional<DomainPolicy> findDomain(String domain);
        List<String> findAliases(String address);
        boolean hasMailbox(String address);
    }

    public static final class RoutingException extends Exception {
        private final boolean limit;

        RoutingException(String message, boolean limit) {
            super(message);
            this.limit = limit;
        }

        public boolean isLimit() { return limit; }
    }

    private final Lookup lookup;
    private final boolean allowExternalForwarding;
    private final int maxDepth;
    private final int maxRecipients;

    public RecipientDirectory(Lookup lookup, boolean allowExternalForwarding, int maxDepth, int maxRecipients) {
        if (lookup == null || maxDepth <= 0 || maxDepth > 100 || maxRecipients <= 0 || maxRecipients > 100000) {
            throw new IllegalArgumentException("Invalid recipient routing configuration");
        }
        this.lookup = lookup;
        this.allowExternalForwarding = allowExternalForwarding;
        this.maxDepth = maxDepth;
        this.maxRecipients = maxRecipients;
    }

    private static BasicConfig configuration() {
        return new BasicConfig(Config.getServer().getMapProperty("recipientRouting"));
    }

    public static boolean isEnabled() {
        return configuration().getBooleanProperty("enabled", false);
    }

    public static int configuredMaxRecipients() {
        return Math.toIntExact(configuration().getLongProperty("maxRecipients", 100L));
    }

    public static RecipientDirectory configured() {
        return new RecipientDirectory(SqlAuthManager.getUserLookup(),
                configuration().getBooleanProperty("allowExternalForwarding", false),
                Math.toIntExact(configuration().getLongProperty("maxDepth", 10L)), configuredMaxRecipients());
    }

    /**
     * Returns final address-to-locality routes. No partial expansion is returned
     * when any branch fails. External submission roots are not alias forwards.
     */
    public Map<String, Boolean> resolve(String address, boolean inbound) throws RoutingException {
        Map<String, Boolean> targets = new LinkedHashMap<>();
        expand(normalize(address), !inbound, false, 0, new LinkedHashSet<>(), targets, new int[]{0});
        return java.util.Collections.unmodifiableMap(targets);
    }

    private void expand(String address, boolean externalRoot, boolean forwarding, int depth,
                        Set<String> path, Map<String, Boolean> targets, int[] visited) throws RoutingException {
        if (depth > maxDepth || ++visited[0] > (long) maxRecipients * (maxDepth + 1)) {
            throw new RoutingException("Recipient expansion limit exceeded", true);
        }
        if (!path.add(address)) {
            throw new RoutingException("Recipient routing loop", false);
        }
        try {
            String domain = address.substring(address.lastIndexOf('@') + 1);
            Optional<DomainPolicy> policy = lookup.findDomain(domain);
            if (policy.isEmpty()) {
                if (externalRoot || (forwarding && allowExternalForwarding)) {
                    addTarget(address, false, targets);
                    return;
                }
                throw new RoutingException("Recipient domain is not served", false);
            }
            if (!policy.get().enabled()) {
                throw new RoutingException("Recipient domain is disabled", false);
            }
            List<String> aliases = lookup.findAliases(address);
            if (aliases.size() > maxRecipients) {
                throw new RoutingException("Recipient expansion limit exceeded", true);
            }
            if (!aliases.isEmpty()) {
                for (String target : aliases) {
                    String normalized = normalize(target);
                    if (address.equals(normalized) && lookup.hasMailbox(address)) {
                        addTarget(address, true, targets);
                    } else {
                        expand(normalized, false, true, depth + 1, path, targets, visited);
                    }
                }
            } else if (lookup.hasMailbox(address)) {
                addTarget(address, true, targets);
            } else {
                int plus = address.indexOf('+');
                int at = address.lastIndexOf('@');
                if (policy.get().plusAddressing() && plus > 0 && plus < at) {
                    expand(address.substring(0, plus) + address.substring(at), false, forwarding,
                            depth + 1, path, targets, visited);
                } else if (policy.get().catchAll() != null && !policy.get().catchAll().isBlank()) {
                    expand(normalize(policy.get().catchAll()), false, true, depth + 1, path, targets, visited);
                } else {
                    throw new RoutingException("Recipient mailbox is not known", false);
                }
            }
        } finally {
            path.remove(address);
        }
    }

    private void addTarget(String address, boolean local, Map<String, Boolean> targets) throws RoutingException {
        targets.put(address, local);
        if (targets.size() > maxRecipients) {
            throw new RoutingException("Recipient expansion limit exceeded", true);
        }
    }

    private static String normalize(String address) throws RoutingException {
        if (address == null) throw new RoutingException("Invalid recipient address", false);
        String normalized = SqlUserLookup.normalize(address);
        try {
            InternetAddress parsed = new InternetAddress(normalized, true);
            parsed.validate();
            if (parsed.isGroup() || !normalized.equals(parsed.getAddress())
                    || normalized.lastIndexOf('@') <= 0) {
                throw new AddressException("Expected a single mailbox");
            }
        } catch (AddressException e) {
            throw new RoutingException("Invalid recipient address", false);
        }
        return normalized;
    }
}
