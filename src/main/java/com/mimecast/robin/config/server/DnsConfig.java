package com.mimecast.robin.config.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the dnsjava resolver used by all DNS lookups in Robin
 * (RBL checks, MX resolution, DANE, FCrDNS, etc.).
 *
 * <p>When {@code servers} is empty, the JVM system resolver is used.
 * When one or more servers are listed, an {@code ExtendedResolver} is created
 * so that failed or timed-out queries are retried against the next server in
 * the list.
 *
 * <p>Example config block in {@code server.json5}:
 * <pre>
 * dns: {
 *   servers: ["127.0.0.1"],
 *   timeoutSeconds: 5,
 *   tcp: false,
 *   port: 53
 * }
 * </pre>
 */
public class DnsConfig {
    private final Map<String, Object> map;

    public DnsConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * DNS server addresses to query.
     * Empty list means use the JVM system resolver (from {@code /etc/resolv.conf}).
     */
    @SuppressWarnings("unchecked")
    public List<String> getServers() {
        if (map.containsKey("servers")) {
            return (List<String>) map.get("servers");
        }
        return Collections.emptyList();
    }

    /**
     * Per-query timeout in seconds. Default: 5.
     */
    public int getTimeoutSeconds() {
        return map.containsKey("timeoutSeconds")
                ? ((Number) map.get("timeoutSeconds")).intValue()
                : 5;
    }

    /**
     * Force TCP for all DNS queries instead of UDP.
     * Useful when UDP responses are truncated or for debugging.
     * Default: false.
     */
    public boolean isTcp() {
        return map.containsKey("tcp") && (Boolean) map.get("tcp");
    }

    /**
     * DNS server port. Default: 53.
     * Override when using a resolver on a non-standard port (e.g. unbound on 5353).
     */
    public int getPort() {
        return map.containsKey("port")
                ? ((Number) map.get("port")).intValue()
                : 53;
    }

    /**
     * Whether the shared dnsjava response cache is enabled. Default: true.
     * Disable only for debugging — without caching every DNS lookup hits the network.
     */
    public boolean isCacheEnabled() {
        return !map.containsKey("cacheEnabled") || (Boolean) map.get("cacheEnabled");
    }

    /**
     * Maximum number of positive (successful) entries in the shared cache. Default: 0 (dnsjava default).
     * Tune this on high-traffic servers where memory is constrained.
     * 0 means use the dnsjava built-in default.
     */
    public int getCacheMaxEntries() {
        return map.containsKey("cacheMaxEntries")
                ? ((Number) map.get("cacheMaxEntries")).intValue()
                : 0;
    }

    /**
     * Maximum number of negative (NXDOMAIN / NODATA) entries in the shared cache. Default: 0 (dnsjava default).
     * Negative caching prevents repeated DNS lookups for non-existent domains.
     * 0 means use the dnsjava built-in default.
     */
    public int getCacheMaxNegativeEntries() {
        return map.containsKey("cacheMaxNegativeEntries")
                ? ((Number) map.get("cacheMaxNegativeEntries")).intValue()
                : 0;
    }
}
