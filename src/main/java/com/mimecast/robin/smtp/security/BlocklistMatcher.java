package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.BlocklistConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Utility class for efficiently matching IP addresses against a blocklist.
 * <p>Supports both individual IP addresses and CIDR notation for network blocks.
 * <p>This class is thread-safe and designed to work with config auto-reload.
 */
public class BlocklistMatcher {
    private static final Logger log = LogManager.getLogger(BlocklistMatcher.class);

    /**
     * Checks if the given IP address is in the blocklist.
     * <p>This method creates a new matcher instance on each call to support config auto-reload.
     *
     * @param ipAddress The IP address to check (as a string).
     * @param config    The blocklist configuration.
     * @return true if the IP address is blocked, false otherwise.
     */
    public static boolean isBlocked(String ipAddress, BlocklistConfig config) {
        // If blocklist is not enabled, don't block anything
        if (!config.isEnabled()) {
            return false;
        }

        List<String> entries = config.getEntries();
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        try {
            // Parse the incoming IP address
            InetAddress address = InetAddress.getByName(ipAddress);
            
            // Check against each entry in the blocklist
            for (String entry : entries) {
                if (matches(address, entry)) {
                    log.info("Blocked connection from {} (matched blocklist entry: {})", ipAddress, entry);
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            log.warn("Failed to parse IP address: {}", ipAddress);
            return false;
        }

        return false;
    }

    /**
     * Checks if an IP address matches a blocklist entry.
     * Supports both single IP addresses and CIDR notation.
     *
     * @param address The IP address to check.
     * @param entry   The blocklist entry (can be single IP or CIDR).
     * @return true if the address matches the entry, false otherwise.
     */
    private static boolean matches(InetAddress address, String entry) {
        entry = entry.trim();
        
        try {
            // Check if entry contains CIDR notation
            if (entry.contains("/")) {
                return matchesCIDR(address, entry);
            } else {
                // Simple IP address match
                InetAddress entryAddress = InetAddress.getByName(entry);
                return address.equals(entryAddress);
            }
        } catch (Exception e) {
            log.warn("Failed to parse blocklist entry: {}", entry, e);
            return false;
        }
    }

    /**
     * Checks if an IP address matches a CIDR block.
     *
     * @param address The IP address to check.
     * @param cidr    The CIDR notation (e.g., "192.168.1.0/24").
     * @return true if the address is in the CIDR block, false otherwise.
     */
    private static boolean matchesCIDR(InetAddress address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                log.warn("Invalid CIDR notation: {}", cidr);
                return false;
            }

            InetAddress networkAddress = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            // Ensure both addresses are the same type (IPv4 or IPv6)
            byte[] addressBytes = address.getAddress();
            byte[] networkBytes = networkAddress.getAddress();
            
            if (addressBytes.length != networkBytes.length) {
                return false;
            }

            // Validate prefix length for IPv4 (0-32) or IPv6 (0-128)
            int maxPrefixLength = addressBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefixLength) {
                log.warn("Invalid CIDR prefix length {} for address type (max {}): {}", 
                    prefixLength, maxPrefixLength, cidr);
                return false;
            }

            // Calculate the number of full bytes and remaining bits to check
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Ensure fullBytes doesn't exceed address length
            if (fullBytes > addressBytes.length) {
                return false;
            }

            // Check full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (addressBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // Check remaining bits if any
            if (remainingBits > 0 && fullBytes < addressBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addressBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Failed to match CIDR: {}", cidr, e);
            return false;
        }
    }
}
