package com.mimecast.robin.mtasts;

import com.mimecast.robin.mtasts.assets.DnsRecord;
import com.mimecast.robin.mtasts.client.XBillDnsRecordClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * MXResolver encapsulates MX record resolution with MTA-STS preference.
 *
 * Resolution order:
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
            return optionalDnsRecords.get();
        }

        log.warn("No MX records found for domain: {}", domain);
        return Collections.emptyList();
    }
}

