package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Process-local authenticated principal/domain quotas, independent of peer IP.
 * Attempts and uploaded bytes consume budget even if the transaction later fails.
 */
public final class SubmissionLimits {
    private static final Logger log = LogManager.getLogger(SubmissionLimits.class);
    private static final SubmissionLimits INSTANCE = new SubmissionLimits(System::nanoTime, 10000);

    public enum Resource { MESSAGES, RECIPIENTS, BYTES }

    public static final class QuotaException extends IOException {
        private QuotaException(String message) { super(message); }
        private QuotaException(String message, Throwable cause) { super(message, cause); }
    }

    private record Budget(long messages, long recipients, long bytes, long connections, long authFailures) {
        static Budget read(BasicConfig config, boolean account) {
            validateKeys(config, account ? Set.of("messages", "recipients", "bytes", "concurrentConnections", "authFailures")
                    : Set.of("messages", "recipients", "bytes", "concurrentConnections"));
            Budget result = new Budget(readLong(config, "messages", 0),
                    readLong(config, "recipients", 0), readLong(config, "bytes", 0),
                    readLong(config, "concurrentConnections", 0), readLong(config, "authFailures", 0));
            if (result.messages < 0 || result.recipients < 0 || result.bytes < 0
                    || result.connections < 0 || result.authFailures < 0) {
                throw new IllegalArgumentException("Submission limits must not be negative");
            }
            return result;
        }
        long maximum(Resource resource) {
            return switch (resource) {
                case MESSAGES -> messages;
                case RECIPIENTS -> recipients;
                case BYTES -> bytes;
            };
        }
    }

    private record Policy(boolean enabled, long windowNanos, Budget account, Budget domain) {
        static Policy read() {
            BasicConfig config = section(Config.getServer().getMap(), "submissionLimits");
            validateKeys(config, Set.of("enabled", "windowSeconds", "account", "domain"));
            long seconds = readLong(config, "windowSeconds", 3600);
            if (seconds <= 0 || seconds > TimeUnit.NANOSECONDS.toSeconds(Long.MAX_VALUE)) {
                throw new IllegalArgumentException("Invalid submission limit window");
            }
            Object enabled = config.getMap().getOrDefault("enabled", false);
            if (!Boolean.TRUE.equals(enabled) && !Boolean.FALSE.equals(enabled)) {
                throw new IllegalArgumentException("Submission limits enabled must be a boolean");
            }
            return new Policy(Boolean.TRUE.equals(enabled), TimeUnit.SECONDS.toNanos(seconds),
                    Budget.read(section(config.getMap(), "account"), true),
                    Budget.read(section(config.getMap(), "domain"), false));
        }
    }

    private static BasicConfig section(Map<?, ?> parent, String name) {
        if (!parent.containsKey(name)) return new BasicConfig(Map.of());
        if (!(parent.get(name) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Invalid submission limit section: " + name);
        }
        return new BasicConfig(map);
    }

    private static void validateKeys(BasicConfig config, Set<String> allowed) {
        for (Object key : config.getMap().keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown submission limit key: " + key);
        }
    }

    private static long readLong(BasicConfig config, String name, long fallback) {
        if (!config.getMap().containsKey(name)) return fallback;
        Object value = config.getMap().get(name);
        if (value == null) throw new IllegalArgumentException("Null submission limit: " + name);
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Submission limit must be an exact integer: " + name, e);
        }
    }

    private static final class Usage {
        long started;
        final long[] quantities = new long[Resource.values().length];
        long failures;
        final Set<String> connections = new HashSet<>();
        Usage(long started) { this.started = started; }
        void resetWindow(long now) {
            java.util.Arrays.fill(quantities, 0);
            failures = 0;
            started = now;
        }
    }

    private final LongSupplier clock;
    private final int capacity;
    private final Map<String, Usage> usage = new HashMap<>();
    private final Map<String, Usage> failedAuthentication = new HashMap<>();
    private final Map<String, String> authenticatedSessions = new HashMap<>();

    public SubmissionLimits(LongSupplier clock, int capacity) {
        if (clock == null || capacity < 2) throw new IllegalArgumentException("Invalid quota tracker");
        this.clock = clock;
        this.capacity = capacity;
    }

    private Usage get(String key, Policy policy, long now) {
        return get(usage, key, policy, now);
    }

    private Usage get(Map<String, Usage> entries, String key, Policy policy, long now) {
        Usage result = entries.get(key);
        if (result == null) {
            if (entries.size() >= capacity) {
                entries.entrySet().removeIf(entry -> entry.getValue().connections.isEmpty()
                        && now - entry.getValue().started >= policy.windowNanos);
            }
            if (entries.size() >= capacity) throw new IllegalStateException("Submission quota tracking capacity exceeded");
            result = new Usage(now);
            entries.put(key, result);
        } else if (now - result.started >= policy.windowNanos) {
            result.resetWindow(now);
        }
        return result;
    }

    private static String normalize(String principal) {
        if (principal == null || principal.isBlank()) throw new IllegalArgumentException("Missing submission principal");
        return principal.trim().toLowerCase(Locale.ROOT);
    }

    private static String domain(String principal) {
        int at = principal.lastIndexOf('@');
        return at > 0 && at < principal.length() - 1 ? principal.substring(at + 1) : null;
    }

    public synchronized boolean canAuthenticate(String principal) {
        Policy policy = Policy.read();
        if (!policy.enabled || policy.account.authFailures == 0) return true;
        String key = normalize(principal);
        if (!failedAuthentication.containsKey(key)) return true;
        return get(failedAuthentication, key, policy, clock.getAsLong()).failures < policy.account.authFailures;
    }

    public synchronized void recordFailure(String principal) {
        Policy policy = Policy.read();
        if (!policy.enabled || policy.account.authFailures == 0) return;
        Usage account = get(failedAuthentication, normalize(principal), policy, clock.getAsLong());
        if (account.failures < Long.MAX_VALUE) account.failures++;
    }

    public synchronized boolean admit(String principal, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Missing submission session ID");
        Policy policy = Policy.read();
        if (!policy.enabled) return true;
        String accountKey = normalize(principal);
        String existing = authenticatedSessions.get(sessionId);
        if (existing != null) return existing.equals(accountKey);
        if (authenticatedSessions.size() >= capacity) {
            throw new IllegalStateException("Submission session tracking capacity exceeded");
        }
        long now = clock.getAsLong();
        Usage account = get("account:" + accountKey, policy, now);
        String domainKey = domain(accountKey);
        Usage realm = domainKey == null ? null : get("domain:" + domainKey, policy, now);
        if (!canAuthenticate(principal)
                || (policy.account.connections > 0 && account.connections.size() >= policy.account.connections)
                || (realm != null && policy.domain.connections > 0 && realm.connections.size() >= policy.domain.connections)) {
            return false;
        }
        account.connections.add(sessionId);
        if (realm != null) realm.connections.add(sessionId);
        authenticatedSessions.put(sessionId, accountKey);
        return true;
    }

    public synchronized void release(String sessionId) {
        String principal = authenticatedSessions.remove(sessionId);
        if (principal == null) return;
        Usage account = usage.get("account:" + principal);
        if (account != null) account.connections.remove(sessionId);
        Usage realm = usage.get("domain:" + domain(principal));
        if (realm != null) realm.connections.remove(sessionId);
    }

    public boolean consume(String principal, Resource resource, long amount) {
        return consume(principal, resource, amount, Policy.read());
    }

    private synchronized boolean consume(String principal, Resource resource, long amount, Policy policy) {
        if (amount < 0) throw new IllegalArgumentException("Negative quota charge");
        if (!policy.enabled) return true;
        String normalized = normalize(principal);
        long now = clock.getAsLong();
        Usage account = get("account:" + normalized, policy, now);
        String domainKey = domain(normalized);
        Usage realm = domainKey == null ? null : get("domain:" + domainKey, policy, now);
        if (!fits(account, resource, amount, policy.account)
                || (realm != null && !fits(realm, resource, amount, policy.domain))) return false;
        charge(account, resource, amount);
        if (realm != null) charge(realm, resource, amount);
        return true;
    }

    private static boolean fits(Usage usage, Resource resource, long amount, Budget budget) {
        long maximum = budget.maximum(resource);
        return maximum == 0 || amount <= maximum - usage.quantities[resource.ordinal()];
    }

    private static void charge(Usage usage, Resource resource, long amount) {
        int index = resource.ordinal();
        usage.quantities[index] = amount > Long.MAX_VALUE - usage.quantities[index]
                ? Long.MAX_VALUE : usage.quantities[index] + amount;
    }

    public static boolean allowAuthentication(Connection connection, boolean verified) throws IOException {
        if (connection.getSession().getUsername().isBlank()) return true;
        try {
            boolean allowed = verified
                    ? INSTANCE.admit(connection.getSession().getUsername(), connection.getSession().getUID())
                    : INSTANCE.canAuthenticate(connection.getSession().getUsername());
            if (allowed) return true;
            log.warn("Authenticated submission admission limit exceeded");
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Submission limit admission unavailable: {}", e.getMessage());
        }
        connection.write("454 4.7.0 Submission admission is temporarily limited");
        return false;
    }

    public static boolean authenticationFailed(Connection connection) throws IOException {
        try {
            if (!connection.getSession().getUsername().isBlank()) INSTANCE.recordFailure(connection.getSession().getUsername());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Submission auth-failure tracking unavailable: {}", e.getMessage());
            connection.write("454 4.7.0 Authentication tracking is unavailable");
            return false;
        }
        connection.write(SmtpResponses.AUTH_FAILED_535);
        return false;
    }

    public static boolean check(Connection connection, Resource resource, long amount) throws IOException {
        if (!connection.getSession().isAuth()) return true;
        try {
            if (INSTANCE.consume(connection.getSession().getUsername(), resource, amount)) return true;
            log.warn("Submission quota exceeded: resource={}", resource);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Submission quota unavailable: {}", e.getMessage());
        }
        connection.write(resource == Resource.RECIPIENTS ? "452 4.7.0 Submission recipient limit exceeded"
                : "451 4.7.0 Submission limit exceeded");
        return false;
    }

    public static OutputStream receivingStream(Connection connection, OutputStream delegate) throws IOException {
        Policy policy;
        try {
            policy = Policy.read();
        } catch (IllegalArgumentException e) {
            throw new QuotaException("Invalid submission byte quota configuration", e);
        }
        if (!policy.enabled || !connection.getSession().isAuth()) return delegate;
        return new FilterOutputStream(delegate) {
            private void reserve(int count) throws IOException {
                if (connection.getSession().isAuth()) {
                    try {
                        if (!INSTANCE.consume(connection.getSession().getUsername(), Resource.BYTES, count, policy)) {
                            throw new QuotaException("Authenticated submission byte limit exceeded");
                        }
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw new QuotaException("Submission byte quota unavailable", e);
                    }
                }
            }
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                reserve(length);
                out.write(bytes, offset, length);
            }
        };
    }

    public static void releaseConnection(Connection connection) {
        INSTANCE.release(connection.getSession().getUID());
    }
}
