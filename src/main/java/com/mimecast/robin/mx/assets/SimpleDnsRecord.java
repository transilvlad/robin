package com.mimecast.robin.mx.assets;

/**
 * Simple immutable DNS record used for synthesized routing entries,
 * such as implicit MX fallback targets.
 */
public final class SimpleDnsRecord implements DnsRecord {
    private final String value;
    private final int priority;

    public SimpleDnsRecord(String value, int priority) {
        this.value = value;
        this.priority = priority;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public int getPriority() {
        return priority;
    }
}
