package com.mimecast.robin.config.server;

import com.mimecast.robin.config.ConfigFoundation;

import java.util.Map;

/**
 * Listener configuration for SMTP ports.
 *
 * <p>This class provides type safe access to listener-specific configuration.
 * <p>Each SMTP port (smtp, secure, submission) can have its own configuration.
 */
public class ListenerConfig extends ConfigFoundation {

    /**
     * Constructs a new ListenerConfig instance.
     */
    public ListenerConfig() {
        super();
    }

    /**
     * Constructs a new ListenerConfig instance with configuration map.
     *
     * @param map Configuration map.
     */
    public ListenerConfig(Map<String, Object> map) {
        super();
        this.map = map;
    }

    /**
     * Gets backlog size.
     *
     * @return Backlog size.
     */
    public int getBacklog() {
        return Math.toIntExact(getLongProperty("backlog", 25L));
    }

    /**
     * Gets minimum pool size.
     *
     * @return Thread pool min size.
     */
    public int getMinimumPoolSize() {
        return Math.toIntExact(getLongProperty("minimumPoolSize", 1L));
    }

    /**
     * Gets maximum pool size.
     *
     * @return Thread pool max size.
     */
    public int getMaximumPoolSize() {
        return Math.toIntExact(getLongProperty("maximumPoolSize", 10L));
    }

    /**
     * Gets thread keep alive time.
     *
     * @return Time in seconds.
     */
    public int getThreadKeepAliveTime() {
        return Math.toIntExact(getLongProperty("threadKeepAliveTime", 60L));
    }

    /**
     * Gets transactions limit.
     * <p>This defines how many commands will be processed before breaking receipt loop.
     *
     * @return Transactions limit.
     */
    public int getTransactionsLimit() {
        return Math.toIntExact(getLongProperty("transactionsLimit", 305L));
    }

    /**
     * Gets recipients limit.
     * <p>This defines how many recipients will be processed before rejecting them.
     *
     * @return Recipients limit.
     */
    public int getRecipientsLimit() {
        return Math.toIntExact(getLongProperty("recipientsLimit", 100L));
    }

    /**
     * Gets envelope limit.
     * <p>This defines how many envelopes will be processed before breaking receipt loop.
     *
     * @return Envelope limit.
     */
    public int getEnvelopeLimit() {
        return Math.toIntExact(getLongProperty("envelopeLimit", 100L));
    }

    /**
     * Gets email size limit.
     * <p>This defines how big emails will be accepted.
     *
     * @return Email size limit.
     */
    public int getEmailSizeLimit() {
        return Math.toIntExact(getLongProperty("emailSizeLimit", 10242400L)); // 10 MB.
    }

    /**
     * Gets error limit.
     * <p>This defines how many syntax errors should be permitted before interrupting the receipt.
     *
     * @return Error limit.
     */
    public int getErrorLimit() {
        return Math.toIntExact(getLongProperty("errorLimit", 3L));
    }
}

