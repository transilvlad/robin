package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.assets.StsPolicy;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.mx.dane.DaneChecker;
import com.mimecast.robin.mx.dane.DaneRecord;
import com.mimecast.robin.smtp.security.SecureMxRecord;
import com.mimecast.robin.smtp.security.SecurityPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * MXResolver encapsulates MX record resolution with DANE and MTA-STS support.
 * <p>Resolution order follows RFC 8461 Section 2 and RFC 7672:
 * <ol>
 *   <li>Check for DANE TLSA records on MX hosts (RFC 7672)</li>
 *   <li>If DANE is available, use it (DANE takes precedence per RFC 8461)</li>
 *   <li>If no DANE, attempt MTA-STS Strict MX records (RFC 8461)</li>
 *   <li>If no MTA-STS, fall back to regular MX records via DNS</li>
 * </ol>
 * <p><strong>Important:</strong> Per RFC 8461 Section 2, "senders who implement MTA-STS
 * validation MUST NOT allow MTA-STS Policy validation to override a failing DANE validation."
 * This means DANE always takes priority when present.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 */
public class MXResolver {
    private static final Logger log = LogManager.getLogger(MXResolver.class);

    /**
     * Resolves MX records with security policies for RFC-compliant secure delivery.
     * <p>Implements RFC 8461 Section 2 and RFC 7672 priority: DANE takes precedence over MTA-STS.
     * <p>Resolution process:
     * <ol>
     *   <li>Get regular MX records for the domain</li>
     *   <li>Check each MX host for DANE TLSA records</li>
     *   <li>If DANE is available, attach DANE policies</li>
     *   <li>If no DANE, check MTA-STS policy and attach MTA-STS policies</li>
     *   <li>If neither, attach opportunistic policies</li>
     * </ol>
     *
     * @param domain Domain to resolve.
     * @return List of SecureMxRecord with security policies, possibly empty if no MX found.
     */
    public List<SecureMxRecord> resolveSecureMx(String domain) {
        // Step 1: Get regular MX records via DNS client.
        var optionalDnsRecords = new XBillDnsRecordClient().getMxRecords(domain);
        if (optionalDnsRecords.isEmpty() || optionalDnsRecords.get().isEmpty()) {
            log.warn("No MX records found for domain: {}", domain);
            return Collections.emptyList();
        }

        List<DnsRecord> mxRecords = optionalDnsRecords.get();
        log.debug("Found {} MX records for domain: {}", mxRecords.size(), domain);

        // Step 2: Check for DANE TLSA records on each MX host.
        Map<String, List<DaneRecord>> daneRecordsByMx = new HashMap<>();
        boolean daneAvailable = false;

        for (DnsRecord mx : mxRecords) {
            String mxHostname = mx.getValue();
            List<DaneRecord> daneRecords = DaneChecker.checkDane(mxHostname);
            if (!daneRecords.isEmpty()) {
                log.info("DANE TLSA records found for MX host: {} (domain: {})", mxHostname, domain);
                daneRecordsByMx.put(mxHostname, daneRecords);
                daneAvailable = true;
            }
        }

        // Step 3: If DANE is available, attach DANE policies (skip MTA-STS per RFC 8461).
        if (daneAvailable) {
            log.info("DANE enabled for domain: {} - using DANE policies (skipping MTA-STS per RFC 8461)", domain);
            List<SecureMxRecord> secureMxRecords = new ArrayList<>();
            for (DnsRecord mx : mxRecords) {
                String mxHostname = mx.getValue();
                SecurityPolicy policy;

                if (daneRecordsByMx.containsKey(mxHostname)) {
                    // This MX has DANE - mandatory TLS with TLSA validation.
                    policy = SecurityPolicy.dane(mxHostname, daneRecordsByMx.get(mxHostname));
                } else {
                    // This MX doesn't have DANE, but domain has DANE on other MXs.
                    // Per RFC 7672, we should treat this as opportunistic for this specific MX.
                    policy = SecurityPolicy.opportunistic(mxHostname);
                    log.debug("MX {} has no DANE records while domain {} has DANE on other MXs", mxHostname, domain);
                }

                secureMxRecords.add(new SecureMxRecord(mx, policy));
            }
            return secureMxRecords;
        }

        log.debug("No DANE TLSA records found for domain: {} - checking MTA-STS", domain);

        // Step 4: No DANE, check MTA-STS policy.
        StrictMx strictMx = new StrictMx(domain);
        StsPolicy stsPolicy = strictMx.getPolicy();

        if (stsPolicy != null && stsPolicy.isValid()) {
            log.info("MTA-STS policy found for domain: {} - mode: {}", domain, stsPolicy.getMode());

            // Filter MX records that match the MTA-STS policy.
            List<SecureMxRecord> secureMxRecords = new ArrayList<>();
            for (DnsRecord mx : mxRecords) {
                String mxHostname = mx.getValue();
                if (stsPolicy.matchMx(mxHostname)) {
                    SecurityPolicy policy = SecurityPolicy.mtaSts(mxHostname, stsPolicy.getMode().toString());
                    secureMxRecords.add(new SecureMxRecord(mx, policy));
                } else {
                    log.warn("MX {} does not match MTA-STS policy for domain {}", mxHostname, domain);
                }
            }

            if (!secureMxRecords.isEmpty()) {
                return secureMxRecords;
            } else {
                log.warn("No MX records match MTA-STS policy for domain: {}", domain);
                // Fall through to opportunistic.
            }
        }

        log.debug("No MTA-STS policy found for domain: {} - using opportunistic security", domain);

        // Step 5: No DANE or MTA-STS, return with opportunistic policies.
        List<SecureMxRecord> secureMxRecords = new ArrayList<>();
        for (DnsRecord mx : mxRecords) {
            SecurityPolicy policy = SecurityPolicy.opportunistic(mx.getValue());
            secureMxRecords.add(new SecureMxRecord(mx, policy));
        }
        return secureMxRecords;
    }

    /**
     * Resolves MX records for a domain with DANE and MTA-STS support.
     * <p><strong>Note:</strong> This method returns raw DnsRecords without security policy information.
     * <br>For RFC-compliant secure delivery, use {@link #resolveSecureMx(String)} instead.
     * <p>Implements RFC 8461 Section 2 priority: DANE takes precedence over MTA-STS.
     * <p>Resolution process:
     * <ol>
     *   <li>Get regular MX records for the domain</li>
     *   <li>Check each MX host for DANE TLSA records</li>
     *   <li>If any MX has DANE, return regular MX (DANE available, skip MTA-STS)</li>
     *   <li>If no DANE, check MTA-STS policy and filter MX records</li>
     *   <li>If no MTA-STS, return regular MX records</li>
     * </ol>
     * <p>Use {@link #resolveSecureMx(String)} for security policy enforcement.
     *
     * @param domain Domain to resolve.
     * @return List of DnsRecord, possibly empty if none found.
     */
    public List<DnsRecord> resolveMx(String domain) {
        // Step 1: Get regular MX records via DNS client.
        var optionalDnsRecords = new XBillDnsRecordClient().getMxRecords(domain);
        if (optionalDnsRecords.isEmpty() || optionalDnsRecords.get().isEmpty()) {
            log.warn("No MX records found for domain: {}", domain);
            return Collections.emptyList();
        }

        List<DnsRecord> mxRecords = optionalDnsRecords.get();
        log.debug("Found {} MX records for domain: {}", mxRecords.size(), domain);

        // Step 2: Check for DANE TLSA records on any MX host.
        // Per RFC 8461 Section 2: DANE takes precedence over MTA-STS.
        boolean daneAvailable = false;
        for (DnsRecord mx : mxRecords) {
            String mxHostname = mx.getValue();
            if (DaneChecker.isDaneEnabled(mxHostname)) {
                log.info("DANE TLSA records found for MX host: {} (domain: {})", mxHostname, domain);
                daneAvailable = true;
                break; // Found DANE, no need to check others.
            }
        }

        // Step 3: If DANE is available, use regular MX records (skip MTA-STS).
        if (daneAvailable) {
            log.info("DANE enabled for domain: {} - using DANE path (skipping MTA-STS per RFC 8461)", domain);
            return mxRecords;
        }

        log.debug("No DANE TLSA records found for domain: {} - checking MTA-STS", domain);

        // Step 4: No DANE, try MTA-STS Strict MX.
        StrictMx strictMx = new StrictMx(domain);
        List<DnsRecord> stsMxRecords = strictMx.getMxRecords();
        if (!stsMxRecords.isEmpty()) {
            log.info("MTA-STS policy found for domain: {} - using filtered MX records", domain);
            return stsMxRecords;
        }

        log.debug("No MTA-STS policy found for domain: {} - using regular MX records", domain);

        // Step 5: No DANE or MTA-STS, return regular MX records.
        return mxRecords;
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
                continue; // No route for this domain.
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
            // Should never happen for SHA-256, fallback to plain data.
            return data;
        }
    }
}
