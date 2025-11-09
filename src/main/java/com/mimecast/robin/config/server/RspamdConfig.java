package com.mimecast.robin.config.server;

import com.mimecast.robin.config.ConfigFoundation;

import java.util.Map;

/**
 * Rspamd configuration.
 *
 * <p>This class provides type safe access to Rspamd configuration.
 */
public class RspamdConfig extends ConfigFoundation {

    /**
     * Constructs a new RspamdConfig instance.
     *
     * @param map Configuration map.
     */
    public RspamdConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Checks if Rspamd integration is enabled.
     *
     * @return true if enabled, false otherwise.
     */
    public boolean isEnabled() {
        return getBooleanProperty("enabled", false);
    }

    /**
     * Gets Rspamd server host.
     *
     * @return Rspamd server hostname or IP address.
     */
    public String getHost() {
        return getStringProperty("host", "localhost");
    }

    /**
     * Gets Rspamd server port.
     *
     * @return Rspamd server port number.
     */
    public int getPort() {
        return Math.toIntExact(getLongProperty("port", 11333L));
    }

    /**
     * Gets connection timeout in seconds.
     *
     * @return Timeout in seconds.
     */
    public int getTimeout() {
        return Math.toIntExact(getLongProperty("timeout", 30L));
    }

    /**
     * Checks if SPF scanning is enabled.
     *
     * @return true if SPF scanning is enabled, false otherwise.
     */
    public boolean isSpfScanEnabled() {
        return getBooleanProperty("spfScanEnabled", true);
    }

    /**
     * Checks if DKIM scanning is enabled.
     *
     * @return true if DKIM scanning is enabled, false otherwise.
     */
    public boolean isDkimScanEnabled() {
        return getBooleanProperty("dkimScanEnabled", true);
    }

    /**
     * Checks if DMARC scanning is enabled.
     *
     * @return true if DMARC scanning is enabled, false otherwise.
     */
    public boolean isDmarcScanEnabled() {
        return getBooleanProperty("dmarcScanEnabled", true);
    }

    /**
     * Gets spam score threshold for rejecting emails.
     * <p>
     * Emails with score >= rejectThreshold will be rejected with a 5xx error.
     *
     * @return Rejection threshold score.
     */
    public double getRejectThreshold() {
        return getDoubleProperty("rejectThreshold", 7.0);
    }

    /**
     * Gets spam score threshold for discarding emails.
     * <p>
     * Emails with score >= discardThreshold will be silently discarded.
     *
     * @return Discard threshold score.
     */
    public double getDiscardThreshold() {
        return getDoubleProperty("discardThreshold", 15.0);
    }
}
