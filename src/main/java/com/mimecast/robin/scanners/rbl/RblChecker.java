package com.mimecast.robin.scanners.rbl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RBL (Realtime Blackhole List) Checker.
 *
 * <p>This utility class provides methods to check if an IP address is listed in RBL services.
 * It supports checking against multiple RBL providers in parallel and provides timeout functionality.
 *
 * <p>Usage example:
 * <pre>
 * String ip = "192.168.1.1";
 * List<String> rbls = List.of("zen.spamhaus.org", "bl.spamcop.net");
 * List<RblResult> results = RblChecker.checkIpAgainstRbls(ip, rbls);
 * for (RblResult result : results) {
 *     if (result.isListed()) {
 *         System.out.println(ip + " is listed in " + result.getRblProvider());
 *     }
 * }
 * </pre>
 */
public class RblChecker {
    private static final Logger log = LogManager.getLogger(RblChecker.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    /**
     * Check if an IP address is listed in multiple RBL services.
     *
     * @param ip           The IP address to check
     * @param rblProviders List of RBL provider domains
     * @return List of RBL check results
     */
    public static List<RblResult> checkIpAgainstRbls(String ip, List<String> rblProviders) {
        return checkIpAgainstRbls(ip, rblProviders, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Check if an IP address is listed in multiple RBL services with a specified timeout.
     *
     * @param ip             The IP address to check
     * @param rblProviders   List of RBL provider domains
     * @param timeoutSeconds Timeout in seconds for each RBL query
     * @return List of RBL check results
     */
    public static List<RblResult> checkIpAgainstRbls(String ip, List<String> rblProviders, int timeoutSeconds) {
        if (ip == null || ip.isEmpty() || rblProviders == null || rblProviders.isEmpty()) {
            return Collections.emptyList();
        }

        // Create thread pool for parallel queries
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(rblProviders.size(), 10) // Limit max threads
        );

        try {
            // Create a future for each RBL check
            List<CompletableFuture<RblResult>> futures = rblProviders.stream()
                    .map(rbl -> CompletableFuture.supplyAsync(
                            () -> checkIpAgainstRbl(ip, rbl),
                            executor
                    ))
                    .collect(Collectors.toList());

            // Combine all futures into a single future that completes when all checks complete
            CompletableFuture<List<RblResult>> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            ).thenApply(v ->
                    futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList())
            );

            // Wait for completion with timeout
            return allFutures.get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Error checking IP against RBLs: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Check if an IP address is listed in a specific RBL service.
     *
     * @param ip          The IP address to check
     * @param rblProvider The RBL provider domain
     * @return RBL check result
     */
    public static RblResult checkIpAgainstRbl(String ip, String rblProvider) {
        try {
            String reversedIp = reverseIp(ip);
            String lookupDomain = reversedIp + "." + rblProvider;

            log.debug("Checking {} against RBL {}", ip, rblProvider);

            Record[] records = new Lookup(lookupDomain, Type.A).run();

            boolean isListed = (records != null && records.length > 0);
            List<String> aRecords = new ArrayList<>();

            if (isListed) {
                for (Record record : records) {
                    aRecords.add(record.rdataToString());
                }
                log.debug("{} is listed in {} with responses: {}", ip, rblProvider, aRecords);
            }

            return new RblResult(ip, rblProvider, isListed, aRecords);

        } catch (TextParseException e) {
            log.warn("Invalid lookup format for IP {} against {}: {}", ip, rblProvider, e.getMessage());
            return new RblResult(ip, rblProvider, false, Collections.emptyList());
        } catch (Exception e) {
            log.error("Error checking {} against {}: {}", ip, rblProvider, e.getMessage());
            return new RblResult(ip, rblProvider, false, Collections.emptyList());
        }
    }

    /**
     * Reverse an IPv4 address for RBL lookup.
     * E.g., 192.168.0.1 becomes 1.0.168.192
     *
     * @param ip The IP address to reverse
     * @return The reversed IP address
     * @throws IllegalArgumentException If the IP address is invalid
     */
    public static String reverseIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("IP address cannot be null or empty");
        }

        // Validate IP format first
        if (!isValidIp(ip)) {
            throw new IllegalArgumentException("Invalid IP address format: " + ip);
        }

        try {
            // Split the IP into octets
            String[] octets = ip.split("\\.");
            if (octets.length != 4) {
                throw new IllegalArgumentException("IPv4 address must have exactly 4 octets: " + ip);
            }

            // Reverse the order of octets
            return octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0];

        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IllegalArgumentException("Failed to process IP address: " + ip, e);
        }
    }

    /**
     * Check if an IP address is valid.
     *
     * @param ip The IP address to validate
     * @return true if the IP address is valid, false otherwise
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Regex for IPv4 format (doesn't validate octet values)
        String ipv4Pattern = "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$";
        if (!ip.matches(ipv4Pattern)) {
            return false;
        }

        // Validate each octet is between 0-255
        String[] octets = ip.split("\\.");
        for (String octet : octets) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                return false;
            }
        }

        return true;
    }
}
