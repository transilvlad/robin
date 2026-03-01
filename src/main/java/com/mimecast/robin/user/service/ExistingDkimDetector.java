package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimDetectedSelector;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Probes DNS for existing DKIM selectors.
 */
public class ExistingDkimDetector {
    private static final Logger log = LogManager.getLogger(ExistingDkimDetector.class);

    private static final List<String> PROBE_SELECTORS = List.of(
            "default", "mail", "dkim", "email", "smtp",
            "google", "mailchimp", "sendgrid", "ses", "postmark",
            "selector1", "selector2",
            "2023", "2024", "2025", "2026",
            "2024q1", "2024q2", "2024q3", "2024q4",
            "2025q1", "2025q2", "2025q3", "2025q4",
            "k1", "k2"
    );

    private final DkimKeyRepository repository;
    private final ExecutorService executor;
    private final Resolver resolver;

    public ExistingDkimDetector(DkimKeyRepository repository) {
        this(repository, null);
    }

    public ExistingDkimDetector(DkimKeyRepository repository, Resolver resolver) {
        this.repository = repository;
        this.executor = Executors.newFixedThreadPool(10);
        this.resolver = resolver;
    }

    /**
     * Probes for DKIM selectors for the given domain and stores them in the repository.
     *
     * @param domain Domain to probe.
     * @return List of detected selectors.
     */
    public List<DkimDetectedSelector> probe(String domain) {
        log.info("Probing DKIM selectors for domain: {}", domain);
        List<CompletableFuture<Optional<DkimDetectedSelector>>> futures = new ArrayList<>();

        for (String selector : PROBE_SELECTORS) {
            futures.add(CompletableFuture.supplyAsync(() -> probeSelector(domain, selector), executor));
        }

        List<DkimDetectedSelector> detected = new ArrayList<>();
        for (CompletableFuture<Optional<DkimDetectedSelector>> future : futures) {
            try {
                future.get(2, TimeUnit.SECONDS).ifPresent(detected::add);
            } catch (Exception e) {
                // ignore timeout or execution errors for individual probes
            }
        }

        for (DkimDetectedSelector selector : detected) {
            try {
                repository.saveDetectedSelector(selector);
            } catch (Exception e) {
                log.error("Failed to save detected selector: {}", e.getMessage());
            }
        }

        return detected;
    }

    private Optional<DkimDetectedSelector> probeSelector(String domain, String selector) {
        String query = selector + "._domainkey." + domain;
        try {
            Lookup lookup = new Lookup(query, Type.TXT);
            Resolver res = this.resolver;
            if (res == null) {
                res = Lookup.getDefaultResolver();
            }
            if (res == null) {
                res = new ExtendedResolver();
            }
            lookup.setResolver(res);
            lookup.setCache(null); // don't use cache for probing

            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                return Optional.empty();
            }

            for (Record record : records) {
                if (record instanceof TXTRecord) {
                    TXTRecord txt = (TXTRecord) record;
                    String rdata = String.join("", txt.getStrings());
                    return parseDkimRecord(domain, selector, rdata);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to probe selector {} for domain {}: {}", selector, domain, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<DkimDetectedSelector> parseDkimRecord(String domain, String selector, String rdata) {
        Map<String, String> tags = parseTags(rdata);

        // p= is required for a valid DKIM record (can be empty for revocation)
        if (!tags.containsKey("p")) {
            return Optional.empty();
        }

        String p = tags.get("p");

        DkimDetectedSelector detected = new DkimDetectedSelector();
        detected.setDomain(domain);
        detected.setSelector(selector);
        detected.setPublicKeyDns(p);
        detected.setAlgorithm(tags.getOrDefault("k", "rsa"));
        detected.setRevoked(p.isEmpty());

        String t = tags.get("t");
        if (t != null) {
            detected.setTestMode(t.contains("y"));
        }

        return Optional.of(detected);
    }

    private Map<String, String> parseTags(String rdata) {
        Map<String, String> tags = new HashMap<>();
        String[] parts = rdata.split(";");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq).trim().toLowerCase();
                String value = part.substring(eq + 1).trim();
                tags.put(key, value);
            }
        }
        return tags;
    }

    /**
     * Shuts down the executor.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
