package com.mimecast.robin.config.server;

import com.mimecast.robin.config.ConfigFoundation;

import java.util.Map;

/**
 * Vault configuration.
 *
 * <p>This class provides type safe access to HashiCorp Vault configuration.
 */
public class VaultConfig extends ConfigFoundation {

    /**
     * Constructs a new VaultConfig instance.
     *
     * @param map Configuration map.
     */
    public VaultConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Checks if Vault integration is enabled.
     *
     * @return true if enabled, false otherwise.
     */
    public boolean isEnabled() {
        return getBooleanProperty("enabled", false);
    }

    /**
     * Gets Vault server address.
     *
     * @return Vault server URL (e.g., "https://vault.example.com:8200").
     */
    public String getAddress() {
        return getStringProperty("address", "https://vault.example.com:8200");
    }

    /**
     * Gets Vault authentication token.
     *
     * @return Vault token or path to token file.
     */
    public String getToken() {
        return getStringProperty("token", "");
    }

    /**
     * Gets Vault namespace (for Vault Enterprise).
     *
     * @return Vault namespace, or null if not configured.
     */
    public String getNamespace() {
        return getStringProperty("namespace", null);
    }

    /**
     * Checks if TLS verification should be skipped.
     *
     * @return true to skip TLS verification, false otherwise.
     */
    public boolean isSkipTlsVerification() {
        return getBooleanProperty("skipTlsVerification", false);
    }

    /**
     * Gets connection timeout in seconds.
     *
     * @return Timeout in seconds.
     */
    public int getConnectTimeout() {
        return Math.toIntExact(getLongProperty("connectTimeout", 30L));
    }

    /**
     * Gets read timeout in seconds.
     *
     * @return Timeout in seconds.
     */
    public int getReadTimeout() {
        return Math.toIntExact(getLongProperty("readTimeout", 30L));
    }

    /**
     * Gets write timeout in seconds.
     *
     * @return Timeout in seconds.
     */
    public int getWriteTimeout() {
        return Math.toIntExact(getLongProperty("writeTimeout", 30L));
    }
}
