package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * MXResolver encapsulates MX record resolution with MTA-STS preference.
 * <p>Resolution order:
 * 1) Attempt to resolve MTA-STS Strict MX records.
 * 2) If none, fall back to regular MX records via DNS client.
 */
public class MXResolver {
    private static final Logger log = LogManager.getLogger(MXResolver.class);

    /**
     * Resolves MX records for a domain, preferring MTA-STS Strict MX.
     *
     * @param domain Domain to resolve.
     * @return List of DnsRecord, possibly empty if none found.
     */
    public List<DnsRecord> resolveMx(String domain) {
        // First try MTA-STS Strict MX.
        StrictMx strictMx = new StrictMx(domain);
        List<DnsRecord> mxRecords = strictMx.getMxRecords();
        if (!mxRecords.isEmpty()) {
            return mxRecords;
        }

        log.warn("No MTA-STS MX records found for domain: {}", domain);

        // Fallback to simple MX via DNS client.
        var optionalDnsRecords = new XBillDnsRecordClient().getMxRecords(domain);
        if (optionalDnsRecords.isPresent() && !optionalDnsRecords.get().isEmpty()) {
            log.debug("Found {} MX records for domain: {}", optionalDnsRecords.get().size(), domain);
            return optionalDnsRecords.get();
        }

        log.warn("No MX records found for domain: {}", domain);
        return Collections.emptyList();
    }

    /**
     * Loop through the domains, resolve the MX records, compute a hash for each ordered list of MX
     * records and group them into MXRoute objects unique to each hash while keeping track of the
     * MX servers and the domains they belong to.
     */
    public List<MXRoute> resolveRoutes(List<String> domains) {
        if (domains == null || domains.isEmpty()) return Collections.emptyList();

        Map<String, MXRoute> routesByHash = new LinkedHashMap<>();

        for (String domain : domains) {
            if (domain == null || domain.isBlank()) continue;

            List<DnsRecord> mxRecords = resolveMx(domain);
            if (mxRecords.isEmpty()) {
                log.warn("Skipping domain with no MX: {}", domain);
                continue; // no route for this domain
            }

            // Ensure deterministic order: priority asc, then name asc.
            mxRecords.sort(Comparator
                    .comparingInt(DnsRecord::getPriority)
                    .thenComparing(r -> safeName(r.getValue())));

            String canonical = canonicalize(mxRecords);
            String hash = sha256Hex(canonical);

            MXRoute route = routesByHash.computeIfAbsent(hash, h -> {
                List<MXServer> servers = new ArrayList<>();
                for (DnsRecord r : mxRecords) {
                    servers.add(new MXServer(safeName(r.getValue()), r.getPriority()));
                }
                return new MXRoute(h, servers);
            });

            route.addDomain(domain);
        }

        return new ArrayList<>(routesByHash.values());
    }

    /**
     * Safely normalizes a name by trimming and converting to lowercase.
     *
     * @param name Input name.
     * @return Normalized name.
     */
    private static String safeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a canonical string representation of the MX records list.
     *
     * @param mxRecords List of DnsRecord objects.
     * @return Canonical string in the format "priority:name|priority:name|..."
     */
    private static String canonicalize(List<DnsRecord> mxRecords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mxRecords.size(); i++) {
            DnsRecord r = mxRecords.get(i);
            if (i > 0) sb.append('|');
            sb.append(r.getPriority()).append(':').append(safeName(r.getValue()));
        }
        return sb.toString();
    }

    /**
     * Computes SHA-256 hash of the input data and returns it as a hexadecimal string.
     *
     * @param data Input string to hash.
     * @return Hexadecimal representation of the SHA-256 hash.
     */
    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen for SHA-256, fallback to plain data
            return data;
        }
    }
}
