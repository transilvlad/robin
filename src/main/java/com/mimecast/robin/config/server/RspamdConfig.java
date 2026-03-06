package com.mimecast.robin.config.server;

import com.mimecast.robin.config.ConfigFoundation;

import java.util.HashMap;
import java.util.Map;

/**
 * Rspamd configuration.
 *
 * <p>This class provides type safe access to Rspamd spam filtering configuration.
 *
 * @see com.mimecast.robin.scanners.RspamdClient
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

    /**
     * Gets DKIM signing configuration.
     *
     * @return DkimSigningConfig instance.
     */
    public DkimSigningConfig getDkimSigning() {
        if (map.containsKey("dkimSigning") && map.get("dkimSigning") instanceof Map) {
            return new DkimSigningConfig(getMapProperty("dkimSigning"));
        }
        return new DkimSigningConfig(new HashMap<>());
    }

    /**
     * DKIM signing SQL lookup configuration.
     * <p>
     * Configures the database used to query signing domain and selector pairs for outbound emails.
     * The signing query must return {@code domain} and {@code selector} columns, and may return
     * multiple rows to apply more than one DKIM signature per message.
     */
    public static class DkimSigningConfig extends ConfigFoundation {

        /**
         * Constructs a new DkimSigningConfig instance.
         *
         * @param map Configuration map.
         */
        public DkimSigningConfig(Map<String, Object> map) {
            super(map);
        }

        /**
         * Checks if DKIM signing lookup is enabled.
         *
         * @return True if enabled, false otherwise.
         */
        public boolean isEnabled() {
            return getBooleanProperty("enabled", false);
        }

        /**
         * Gets the JDBC URL for the DKIM signing database.
         *
         * @return JDBC URL string.
         */
        public String getJdbcUrl() {
            return getStringProperty("jdbcUrl", "");
        }

        /**
         * Gets the database username.
         *
         * @return Database username.
         */
        public String getUser() {
            return getStringProperty("user", "");
        }

        /**
         * Gets the database password.
         *
         * @return Database password.
         */
        public String getPassword() {
            return getStringProperty("password", "");
        }

        /**
         * Gets the SQL query used to look up signing domain and selector pairs.
         * <p>
         * The query receives the sender domain as its only parameter ({@code ?}) and must return
         * rows with {@code domain} and {@code selector} columns. Multiple rows are supported.
         *
         * @return SQL query string.
         */
        public String getSigningQuery() {
            return getStringProperty("signingQuery", "");
        }

        /**
         * Gets the filesystem path template for DKIM private key files.
         * <p>
         * The template may contain {@code $domain} and {@code $selector} placeholders which are
         * replaced at runtime. The resolved path must be readable by the Robin process.
         * The key file must be in PKCS8 PEM format (produced by {@code rspamadm dkim_keygen}).
         * <p>
         * Example: {@code /var/lib/rspamd/dkim/$domain.$selector.key}
         *
         * @return Key path template string, or empty string if not configured.
         */
        public String getKeyPath() {
            return getStringProperty("keyPath", "");
        }

        /**
         * Gets the DKIM signing backend to use.
         * <p>
         * Supported values:
         * <ul>
         *   <li>{@code "rspamd"} (default) — delegates to Rspamd via HTTP</li>
         *   <li>{@code "native"} — signs locally using Apache jDKIM, no external service</li>
         * </ul>
         * A plugin override registered via {@code Factories.setDkimSigner()} takes precedence
         * over this setting.
         *
         * @return Backend identifier string.
         */
        public String getBackend() {
            return getStringProperty("backend", "rspamd");
        }
    }
}
