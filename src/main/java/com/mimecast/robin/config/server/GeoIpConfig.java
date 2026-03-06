package com.mimecast.robin.config.server;

import com.mimecast.robin.smtp.security.GeoIpAction;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration class for GeoIP-based connection filtering.
 * <p>Supports per-country allow/block/limit policies using an offline MaxMind GeoLite2 database.
 */
public class GeoIpConfig {
    private final Map<String, Object> map;

    /**
     * Constructs a new GeoIpConfig instance.
     *
     * @param map Configuration map.
     */
    public GeoIpConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * Check if GeoIP filtering is enabled.
     *
     * @return true if GeoIP filtering is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return map.containsKey("enabled") && (Boolean) map.get("enabled");
    }

    /**
     * Gets the path to the MaxMind GeoLite2 country database file.
     *
     * @return Path to the {@code .mmdb} database file.
     */
    public String getDatabasePath() {
        Object value = map.get("databasePath");
        return value instanceof String ? (String) value : "/usr/local/robin/GeoLite2-Country.mmdb";
    }

    /**
     * Gets the default action to apply when the country is not found in the database
     * or when the database is unavailable.
     *
     * @return Default {@link GeoIpAction} (default: ALLOW).
     */
    public GeoIpAction getDefaultAction() {
        return parseAction(map.get("defaultAction"), GeoIpAction.ALLOW);
    }

    /**
     * Gets the action configured for a specific ISO 3166-1 alpha-2 country code.
     * Falls back to {@link #getDefaultAction()} if no specific action is configured.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g., "US", "DE").
     * @return The configured {@link GeoIpAction} for the given country.
     */
    @SuppressWarnings("unchecked")
    public GeoIpAction getCountryAction(String countryCode) {
        if (countryCode == null) {
            return getDefaultAction();
        }
        Object countries = map.get("countries");
        if (countries instanceof Map) {
            Object action = ((Map<String, Object>) countries).get(countryCode.toUpperCase());
            if (action != null) {
                return parseAction(action, getDefaultAction());
            }
        }
        return getDefaultAction();
    }

    /**
     * Parses a string or enum value to a {@link GeoIpAction}.
     *
     * @param value    The value to parse (String or GeoIpAction).
     * @param fallback The fallback action if parsing fails.
     * @return The parsed {@link GeoIpAction}.
     */
    private GeoIpAction parseAction(Object value, GeoIpAction fallback) {
        if (value instanceof String) {
            try {
                return GeoIpAction.valueOf(((String) value).toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
