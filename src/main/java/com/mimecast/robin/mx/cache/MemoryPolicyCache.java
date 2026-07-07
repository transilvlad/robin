package com.mimecast.robin.mx.cache;

import com.mimecast.robin.mx.assets.StsPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory policy cache.
 * <p>Stores StsPolicy instances in a deque map.
 * <p>For memory safety reasons this is limited to 10000 entries.
 * <p>In production environments a cloud cache implementation should be used instead.
 *
 * @author "Vlad Marian" (vmarian@mimecast.com)
 * @link <a href="http://mimecast.com">Mimecast</a>
 * @see StsPolicy
 * @see PolicyCache
 */
public class MemoryPolicyCache extends PolicyCache {

    /**
     * Cache size limit.
     * <p>Package visible for testing.
     */
    static final int MAX_ENTRIES = 10000;

    /**
     * Deque cache.
     */
    private final LinkedHashMap<String, StsPolicy> map = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StsPolicy> eldest) {
            return this.size() > MAX_ENTRIES; // Limit.
        }
    };

    /**
     * Adds policy to cache.
     * <p>Implementation of policy caching.
     *
     * @param policy StsPolicy instance.
     */
    @Override
    protected synchronized void add(StsPolicy policy) {
        map.put(policy.getRecord().getDomain(), policy);
    }

    /**
     * Lookup policy in cache.
     * <p>Implementation of policy lookup in cache.
     *
     * @return StsPolicy instance.
     */
    @Override
    protected synchronized StsPolicy lookup(String domain) {
        return map.get(domain);
    }

    /**
     * Remove policy from cache.
     * <p>Implementation of policy removal from cache.
     *
     * @param domain Domain string.
     */
    @Override
    protected synchronized void remove(String domain) {
        map.remove(domain);
    }

    /**
     * Gets cache size.
     * <p>Implementation of cache size getter.
     * <p>For testing.
     *
     * @return Integer.
     */
    @Override
    synchronized int size() {
        return map.size();
    }
}
