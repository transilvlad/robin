package com.mimecast.robin.mtasts.assets;

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
