package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nonblocking process-local leases for queued destination work.
 */
final class DestinationConcurrency {
    private final int maximum;
    private final int deferSeconds;
    private final Map<String, Integer> active = new HashMap<>();

    DestinationConcurrency(int maximum, int deferSeconds) {
        if (maximum < 0 || deferSeconds <= 0) throw new IllegalArgumentException("Invalid destination concurrency limits");
        this.maximum = maximum;
        this.deferSeconds = deferSeconds;
    }

    static DestinationConcurrency fromConfig() {
        var config = Config.getServer().getQueue().getMap();
        return new DestinationConcurrency(integer(config, "maxConcurrentPerDestination", 0),
                integer(config, "destinationDeferSeconds", 5));
    }

    private static int integer(Map<String, Object> config, String key, int fallback) {
        if (!config.containsKey(key)) return fallback;
        Object value = config.get(key);
        if (value == null) throw new IllegalArgumentException("Null destination limit: " + key);
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Destination limit must be an exact integer: " + key, e);
        }
    }

    synchronized Optional<Lease> tryAcquire(QueueItem<RelaySession> item, long now, long maximumAge) {
        if (maximum == 0 || isExpired(item, now, maximumAge)
                || item.getPayload() == null || item.getPayload().getSession() == null) {
            return Optional.of(new Lease(Set.of()));
        }
        Set<String> keys = keys(item.getPayload());
        if (keys.stream().anyMatch(key -> active.getOrDefault(key, 0) >= maximum)) return Optional.empty();
        keys.forEach(key -> active.merge(key, 1, Integer::sum));
        return Optional.of(new Lease(keys));
    }

    static boolean isExpired(QueueItem<RelaySession> item, long now, long maximumAge) {
        return maximumAge > 0 && now - item.getCreatedAtEpochSeconds() >= maximumAge;
    }

    QueueMutation<RelaySession> defer(QueueItem<RelaySession> item, long now, long maximumAge, String reason) {
        long delay = deferSeconds;
        if (maximumAge > 0) {
            long remaining = maximumAge - Math.max(0, now - item.getCreatedAtEpochSeconds());
            if (remaining <= 0) throw new IllegalStateException("Expired queue item must be processed, not deferred");
            delay = Math.min(delay, remaining);
        }
        long due;
        try {
            due = Math.addExact(now, delay);
        } catch (ArithmeticException e) {
            due = Long.MAX_VALUE;
        }
        return QueueMutation.reschedule(item, due, reason);
    }

    private static Set<String> keys(RelaySession relay) {
        String protocol = relay.getProtocol() == null ? "" : relay.getProtocol().toLowerCase(Locale.ROOT);
        if (protocol.equals("dovecot-lda") || protocol.equals("stalwart-direct")) {
            return Set.of("local:" + protocol);
        }
        Set<String> keys = new LinkedHashSet<>();
        var hosts = relay.getSession().getMx();
        // Classification must not prevent legacy route errors reaching delivery/DSN handling.
        if (hosts == null) return Set.of("unresolved:" + protocol);
        for (String host : hosts) {
            if (host == null) {
                keys.add("unresolved:" + protocol);
                continue;
            }
            String normalized = host.trim().toLowerCase(Locale.ROOT);
            while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
            keys.add(protocol.equals("mx") ? "mx-domain:" + normalized
                    : "endpoint:" + normalized + ":" + relay.getSession().getPort());
        }
        return keys.isEmpty() ? Set.of("unresolved:" + protocol) : Set.copyOf(keys);
    }

    synchronized int activeDestinations() { return active.size(); }

    final class Lease implements AutoCloseable {
        private final Set<String> keys;
        private boolean closed;

        private Lease(Set<String> keys) { this.keys = keys; }

        @Override public void close() {
            synchronized (DestinationConcurrency.this) {
                if (closed) return;
                closed = true;
                for (String key : keys) {
                    active.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
                }
            }
        }
    }
}
