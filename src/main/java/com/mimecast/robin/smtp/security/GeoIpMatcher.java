package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.GeoIpConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Determines the {@link GeoIpAction} for an incoming connection based on its country of origin.
 * <p>Uses {@link GeoIpLookup} to resolve the country code and then applies the policy
 * configured in {@link GeoIpConfig}.
 */
public class GeoIpMatcher {
    private static final Logger log = LogManager.getLogger(GeoIpMatcher.class);

    /**
     * Checks the given IP address against the GeoIP policy and returns the applicable action.
     * <p>Returns {@link GeoIpAction#ALLOW} if GeoIP filtering is disabled.
     *
     * @param ipAddress The remote IP address to check.
     * @param config    The GeoIP configuration.
     * @return The {@link GeoIpAction} to apply to this connection.
     */
    public static GeoIpAction check(String ipAddress, GeoIpConfig config) {
        if (!config.isEnabled()) {
            return GeoIpAction.ALLOW;
        }

        String countryCode = GeoIpLookup.getCountryCode(ipAddress, config.getDatabasePath());

        if (countryCode == null) {
            log.debug("GeoIP country not found for {}, applying default action: {}", ipAddress, config.getDefaultAction());
            return config.getDefaultAction();
        }

        GeoIpAction action = config.getCountryAction(countryCode);
        log.debug("GeoIP check for {} (country: {}): action={}", ipAddress, countryCode, action);
        return action;
    }
}
