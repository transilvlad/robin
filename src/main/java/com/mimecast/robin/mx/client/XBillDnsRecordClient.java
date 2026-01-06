package com.mimecast.robin.mx.client;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.assets.StsRecord;
import com.mimecast.robin.mx.assets.StsReport;
import com.mimecast.robin.mx.assets.XBillDnsRecord;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * XBill Dns Record Client.
 * <p>DNS TXT record lookup client implementation specific for MTA-STS.
 * <p>Uses DNS Java library.
 * <p>A custom resolver can be provided via Lookup.setDefaultResolver().
 * <p>One such resolver is provided for testing purposes.
 *
 * @see Lookup
 * @see LocalDnsResolver
 */
public class XBillDnsRecordClient implements DnsRecordClient {
    private static final Logger log = LogManager.getLogger(XBillDnsRecordClient.class);

    private static final Cache CACHE = new Cache();
    private static final ConcurrentMap<String, PtrCacheEntry> PTR_CACHE = new ConcurrentHashMap<>();

    /**
     * PTR Cache Entry.
     * <p>Holds cached PTR record value and expiry timestamp.
     */
    private static class PtrCacheEntry {
        final String value; // Null means miss.
        final long expiresAt;

        PtrCacheEntry(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Gets DNS TXT MTA-STS record.
     * <p>Will query the <i>_mta-sts.</i> subdomain of the domain provided.
     * <p>If multiple MTA-STS records found it will return none.
     *
     * @param domain Domain string.
     * @return Optional of StsRecord instance.
     */
    @Override
    public Optional<StsRecord> getStsRecord(String domain) {
        Record[] recordList = getRecord("_mta-sts." + domain, Type.TXT);
        if (recordList != null) {
            List<StsRecord> records = new ArrayList<>();
            for (org.xbill.DNS.Record entry : recordList) {
                StsRecord record = new StsRecord(domain, entry.rdataToString());

                if (record.getVersion() != null && record.getVersion().equalsIgnoreCase("STSv1")) {
                    records.add(record);
                }
            }

            if (records.size() == 1) {
                return Optional.of(records.getFirst());
            }
        }

        return Optional.empty();
    }

    /**
     * Gets DNS TXT TLSRPT record.
     * <p>Will query the <i>_smtp._tls.</i> subdomain of the domain provided.
     * <p>If multiple TLSRPT records found it will return none.
     *
     * @param domain Domain string.
     * @return Optional of StsReport instance.
     */
    @Override
    public Optional<StsReport> getRptRecord(String domain) {
        Record[] recordList = getRecord("_smtp._tls." + domain, Type.TXT);
        if (recordList != null) {
            List<StsReport> records = new ArrayList<>();
            for (org.xbill.DNS.Record entry : recordList) {
                StsReport record = new StsReport(entry.rdataToString());

                if (record.getVersion() != null && record.getVersion().equalsIgnoreCase("TLSRPTv1")) {
                    records.add(record);
                }
            }

            if (records.size() == 1) {
                return Optional.of(records.getFirst());
            }
        }

        return Optional.empty();
    }

    /**
     * Gets A MX records.
     *
     * @param domain Domain string.
     * @return Optional of List of MXRecord instances.
     */
    public Optional<List<DnsRecord>> getARecords(String domain) {
        Record[] recordList = getRecord(domain, Type.A);
        if (recordList != null) {
            List<DnsRecord> records = new ArrayList<>();
            for (org.xbill.DNS.Record record : recordList) {
                records.add(new XBillDnsRecord(record));
            }

            if (!records.isEmpty()) {
                return Optional.of(records);
            }
        }

        return Optional.empty();
    }

    /**
     * Gets DNS MX records.
     * <p>Will query for MX records of the domain provided.
     * <p>Will not fall back to A record if none found.
     *
     * @param domain Domain string.
     * @return Optional of List of MXRecord instances.
     */
    public Optional<List<DnsRecord>> getMxRecords(String domain) {
        Record[] recordList = getRecord(domain, Type.MX);
        if (recordList != null) {
            List<DnsRecord> records = new ArrayList<>();
            for (org.xbill.DNS.Record record : recordList) {
                if (record instanceof MXRecord) {
                    records.add(new XBillDnsRecord(record));
                }
            }

            if (!records.isEmpty()) {
                return Optional.of(records);
            }
        }

        return getARecords(domain);
    }

    /**
     * Gets DNS PTR record for a given IP address.
     *
     * @param ipAddress IPv4/IPv6 string.
     * @return Optional with the first PTR target (FQDN) if present.
     */
    public Optional<String> getPtrRecord(String ipAddress) {
        PtrCacheEntry cached = PTR_CACHE.get(ipAddress);
        if (cached != null && cached.expiresAt > Instant.now().toEpochMilli()) {
            return cached.value == null ? Optional.empty() : Optional.of(cached.value);
        }

        try {
            Name addr = org.xbill.DNS.ReverseMap.fromAddress(ipAddress);
            Record[] records = getRecord(addr.toString(true), Type.PTR);
            if (records != null) {
                for (org.xbill.DNS.Record record : records) {
                    if (record instanceof PTRRecord) {
                        String target = ((PTRRecord) record).getTarget().toString(true);
                        cachePtr(ipAddress, target);
                        return Optional.of(target);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PTR lookup failed for {}: {}", ipAddress, e.getMessage());
        }
        cachePtr(ipAddress, null);
        return Optional.empty();
    }

    /**
     * Gets DNS TXT record.
     *
     * @param uri  Lookup URI string.
     * @param type Lookup type int.
     * @return Optional of StsRecord instance.
     */
    private Record[] getRecord(String uri, int type) {
        try {
            Lookup lookup = new Lookup(uri, type);
            // Use default resolver (respects Lookup.setDefaultResolver() for tests)
            // Falls back to ExtendedResolver if no default is set
            Resolver resolver = Lookup.getDefaultResolver();
            if (resolver == null) {
                resolver = new ExtendedResolver();
            }
            lookup.setResolver(resolver);
            lookup.setCache(CACHE);
            return lookup.run();
        } catch (Exception e) {
            log.error("Record URI could not resolve: {} - {}", uri, e.getMessage());
        }

        return new org.xbill.DNS.Record[0];
    }

    /**
     * Caches PTR lookup result.
     *
     * @param ipAddress IP address string.
     * @param value     PTR target string.
     */
    private void cachePtr(String ipAddress, String value) {
        long ttlMs = Config.getServer().getDnsNegativeTtl() * 1000L;
        long expires = Instant.now().toEpochMilli() + ttlMs;
        PTR_CACHE.put(ipAddress, new PtrCacheEntry(value, expires));
    }
}
