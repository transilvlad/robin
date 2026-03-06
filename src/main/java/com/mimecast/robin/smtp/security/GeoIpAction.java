package com.mimecast.robin.smtp.security;

/**
 * The action to apply to a connection based on its originating country.
 *
 * @see GeoIpMatcher
 * @see GeoIpConfig
 */
public enum GeoIpAction {
    /**
     * Allow the connection without any additional restrictions.
     */
    ALLOW,

    /**
     * Reject the connection immediately.
     */
    BLOCK,

    /**
     * Allow the connection but apply reduced per-IP and rate-window limits.
     */
    LIMIT
}
