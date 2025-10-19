package com.mimecast.robin.mx.assets;

/**
 * DNS Record interface.
 */
public interface DnsRecord {

    /**
     * Gets Value.
     *
     * @return Value string.
     */
    String getValue();

    /**
     * Gets priority.
     *
     * @return Priority integer.
     */
    int getPriority();
}
