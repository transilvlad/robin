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
 * Domain Blocklist (DBL) checker.
 *
 * <p>Checks a domain name against domain-based blocklists such as
 * dbl.spamhaus.org, multi.surbl.org, and dbl.nordspam.com.
 *
 * <p>Domain lookups are formatted as {@code <domain>.<dbl-zone>} — unlike IP RBL
 * lookups there is no octet reversal. Checks are run in parallel with a configurable
 * timeout.
 *
 * <p>Note: 127.255.255.254 in a response indicates the query was made from a public
 * resolver that Spamhaus rate-limits. Use a local recursive resolver to avoid this.
 */
public class DblChecker {
    private static final Logger log = LogManager.getLogger(DblChecker.class);

    private static final String RATE_LIMIT_RESPONSE = "127.255.255.254";

    private DblChecker() {}

    /**
     * Checks a domain against multiple DBL providers in parallel.
     *
     * @param domain       Domain to check (e.g. "example.com").
     * @param dblProviders List of DBL provider zones.
     * @param timeoutSeconds Timeout per provider.
     * @return List of results, one per provider.
     */
    public static List<DblResult> checkDomainAgainstDbls(String domain, List<String> dblProviders, int timeoutSeconds) {
        if (domain == null || domain.isEmpty() || dblProviders == null || dblProviders.isEmpty()) {
            return Collections.emptyList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(dblProviders.size(), 10));
        try {
            List<CompletableFuture<DblResult>> futures = dblProviders.stream()
                    .map(dbl -> CompletableFuture.supplyAsync(() -> checkDomainAgainstDbl(domain, dbl), executor))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error checking domain {} against DBLs: {}", domain, e.getMessage());
            return Collections.emptyList();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Checks a domain against a single DBL provider.
     *
     * @param domain      Domain to check.
     * @param dblProvider DBL provider zone.
     * @return Check result.
     */
    public static DblResult checkDomainAgainstDbl(String domain, String dblProvider) {
        try {
            String lookupName = domain + "." + dblProvider;
            log.debug("DBL lookup: {}", lookupName);

            Record[] records = new Lookup(lookupName, Type.A).run();
            boolean isListed = records != null && records.length > 0;
            List<String> aRecords = new ArrayList<>();

            if (isListed) {
                for (Record record : records) {
                    String addr = record.rdataToString();
                    aRecords.add(addr);
                    if (RATE_LIMIT_RESPONSE.equals(addr)) {
                        log.warn("DBL rate-limit response ({}) for {} — use a local resolver", addr, lookupName);
                    }
                }
                log.debug("{} listed in {} with responses: {}", domain, dblProvider, aRecords);
            }

            return new DblResult(domain, dblProvider, isListed, aRecords);

        } catch (TextParseException e) {
            log.warn("Invalid DBL lookup for domain {} against {}: {}", domain, dblProvider, e.getMessage());
            return new DblResult(domain, dblProvider, false, Collections.emptyList());
        } catch (Exception e) {
            log.error("Error checking {} against {}: {}", domain, dblProvider, e.getMessage());
            return new DblResult(domain, dblProvider, false, Collections.emptyList());
        }
    }
}
