package com.mimecast.robin.smtp.security;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Performs country lookups using an offline MaxMind GeoLite2 database.
 * <p>The {@link DatabaseReader} is initialized lazily on first use and is shared
 * across all calls. Use {@link #setReader(DatabaseReader)} to inject a mock reader
 * in tests without requiring an actual {@code .mmdb} file.
 */
public class GeoIpLookup {
    private static final Logger log = LogManager.getLogger(GeoIpLookup.class);

    private static volatile DatabaseReader reader;
    private static volatile String loadedPath;

    /**
     * Private constructor for utility class.
     */
    private GeoIpLookup() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Returns the ISO 3166-1 alpha-2 country code for the given IP address.
     * <p>Initializes the database reader from {@code databasePath} on first call
     * (or when the path changes). Returns {@code null} if the database is unavailable,
     * the IP cannot be resolved, or the IP is not found in the database.
     *
     * @param ipAddress    The IP address to look up.
     * @param databasePath Path to the MaxMind GeoLite2-Country {@code .mmdb} file.
     * @return ISO 3166-1 alpha-2 country code (e.g., "US"), or {@code null} if not found.
     */
    public static String getCountryCode(String ipAddress, String databasePath) {
        if (ipAddress == null || databasePath == null) {
            return null;
        }

        ensureReader(databasePath);
        if (reader == null) {
            return null;
        }

        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            return reader.country(address).getCountry().getIsoCode();
        } catch (UnknownHostException e) {
            log.warn("Failed to parse IP address for GeoIP lookup: {}", ipAddress);
        } catch (GeoIp2Exception e) {
            log.debug("IP not found in GeoIP database: {}", ipAddress);
        } catch (IOException e) {
            log.warn("GeoIP lookup failed for {}: {}", ipAddress, e.getMessage());
        }

        return null;
    }

    /**
     * Initializes or re-initializes the database reader if the path has changed.
     *
     * @param databasePath Path to the MaxMind database file.
     */
    private static void ensureReader(String databasePath) {
        if (reader == null || !databasePath.equals(loadedPath)) {
            synchronized (GeoIpLookup.class) {
                if (reader == null || !databasePath.equals(loadedPath)) {
                    try {
                        DatabaseReader newReader = new DatabaseReader.Builder(new File(databasePath)).build();
                        reader = newReader;
                        loadedPath = databasePath;
                        log.info("GeoIP database loaded from {}", databasePath);
                    } catch (IOException e) {
                        log.error("Failed to load GeoIP database from {}: {}", databasePath, e.getMessage());
                        reader = null;
                    }
                }
            }
        }
    }

    /**
     * Injects a pre-built {@link DatabaseReader} for use in tests.
     * <p>This avoids the need for an actual {@code .mmdb} file during unit testing.
     *
     * @param testReader The mock or pre-built database reader to use, or {@code null} to reset.
     */
    static void setReader(DatabaseReader testReader) {
        reader = testReader;
        loadedPath = null;
    }
}
