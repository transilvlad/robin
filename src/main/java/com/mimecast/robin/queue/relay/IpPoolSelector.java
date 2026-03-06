package com.mimecast.robin.queue.relay;

import com.mimecast.robin.config.server.IpPoolConfig;
import com.mimecast.robin.smtp.session.Session;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weighted round-robin IP pool selector for outbound bind address selection.
 *
 * <p>Holds per-pool counters (reset on JVM restart) and selects a bind IP
 * from the named pool proportionally to configured weights.
 *
 * <p>Subclass and override {@link #selectPoolKey(Session)} to implement
 * domain-based or policy-based pool routing.
 */
public class IpPoolSelector {

    private final IpPoolConfig config;
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * Constructs a new IpPoolSelector with the given pool configuration.
     *
     * @param config IP pool configuration.
     */
    public IpPoolSelector(IpPoolConfig config) {
        this.config = config;
    }

    /**
     * Returns the pool key for a session.
     *
     * <p>Override to implement domain-based or policy-based routing.
     * Default: returns the name of the pool with {@code default: true},
     * or {@code "default"} if no such pool is configured.
     *
     * @param session Session instance.
     * @return Pool key string.
     */
    protected String selectPoolKey(Session session) {
        for (IpPoolConfig.PoolEntry pool : config.getPools()) {
            if (pool.isDefault()) {
                return pool.getName();
            }
        }
        return "default";
    }

    /**
     * Selects a bind IP from the named pool using weighted round-robin.
     *
     * <p>Given addresses {@code [{ip:"10.0.0.1", weight:10}, {ip:"10.0.0.2", weight:5}]},
     * positions 0–9 map to {@code 10.0.0.1} and positions 10–14 map to {@code 10.0.0.2}.
     *
     * @param poolKey Pool name to select from.
     * @return Bind IP address, or {@code null} if no pools are configured or pool not found.
     */
    public String selectAddress(String poolKey) {
        List<IpPoolConfig.PoolEntry> pools = config.getPools();
        if (pools.isEmpty()) return null;

        IpPoolConfig.PoolEntry found = null;
        for (IpPoolConfig.PoolEntry pool : pools) {
            if (pool.getName().equals(poolKey)) {
                found = pool;
                break;
            }
        }
        if (found == null) return null;

        List<IpPoolConfig.AddressEntry> addresses = found.getAddresses();
        if (addresses.isEmpty()) return null;

        int totalWeight = 0;
        for (IpPoolConfig.AddressEntry addr : addresses) {
            totalWeight += addr.getWeight();
        }
        if (totalWeight == 0) return null;

        AtomicLong counter = counters.computeIfAbsent(poolKey, k -> new AtomicLong(0));
        long position = counter.getAndIncrement() % totalWeight;

        int cumulative = 0;
        for (IpPoolConfig.AddressEntry addr : addresses) {
            cumulative += addr.getWeight();
            if (position < cumulative) {
                return addr.getIp();
            }
        }

        // Fallback (should not be reached given correct weight arithmetic).
        return addresses.get(addresses.size() - 1).getIp();
    }
}
