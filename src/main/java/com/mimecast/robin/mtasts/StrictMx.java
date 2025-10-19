package com.mimecast.robin.mtasts;

import com.mimecast.robin.mtasts.assets.DnsRecord;
import com.mimecast.robin.mtasts.assets.StsPolicy;
import com.mimecast.robin.mtasts.exception.*;
import org.apache.commons.validator.ValidatorException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strict MX.
 * <p>Filters MX records against the recipient domain MTA-STS policy if any.
 */
public class StrictMx {
    private static final Logger log = LogManager.getLogger(StrictMx.class);

    /**
     * StrictTransportSecurity static instance.
     * <p>Reusable instance since it instantiates slowly due to the TrustManager.
     */
    private static StrictTransportSecurity strictTransportSecurity = null;

    /**
     * Domain to query.
     */
    private final String domain;

    /**
     * StsPolicy instance.
     */
    private StsPolicy policy;

    /**
     * Constructs a new StrictMxRecords instance.
     *
     * @param domain Recipient domain.
     */
    public StrictMx(String domain) {
        this.domain = domain;

        if (strictTransportSecurity == null) {
            init();
        }
    }

    /**
     * Initialize.
     * <p>Construct a new StrictTransportSecurity if needed.
     */
    private void init() {
        try {
            System.setProperty("com.sun.net.ssl.checkRevocation", "true");
            Security.setProperty("ocsp.enable", "true");

            strictTransportSecurity = new StrictTransportSecurity();
        } catch (Exception e) {
            log.error("Initialization error: {}", e.getMessage());
        }
    }

    /**
     * Gets policy.
     * <p>StsPolicy performs the MX matching.
     *
     * @return StsPolicy instance.
     */
    public StsPolicy getPolicy() {
        if (policy == null) {
            try {
                Optional<StsPolicy> optional = strictTransportSecurity.getPolicy(domain);

                if (optional.isPresent() && optional.get().isValid()) {
                    log.info("Policy loaded");
                    policy = optional.get();
                }

                if (policy.getReport() != null && policy.getReport().isValid()) {
                    log.info("Reporting: {}", policy.getReport().getRua());
                }
            } catch (NoRecordException e) {
                log.debug("No records: {}", e.getMessage());
            } catch (ValidatorException | BadPolicyException | BadRecordException | PolicyFetchErrorException |
                     PolicyWebPKIInvalidException e) {
                log.warn("Exception: {}", e.getMessage());
            }
        }

        return policy;
    }

    /**
     * Gets MX records.
     * <p>Filters found MX records using the STS policy if any.
     *
     * @return List of MXRecord instances.
     */
    public List<DnsRecord> getMxRecords() {
        List<DnsRecord> mxRecords = strictTransportSecurity.getMxRecords(domain);
        log.debug("RAW records count: {}", mxRecords.size());

        if (getPolicy() != null) {
            List<DnsRecord> stsRecords = new ArrayList<>();
            for (DnsRecord mxRecord : mxRecords) {
                if (policy.matchMx(mxRecord.getName())) {
                    stsRecords.add(mxRecord);
                }
            }
            log.debug("STS records count: {}", stsRecords.size());

            if (!stsRecords.isEmpty()) {
                return stsRecords;
            }
        }

        return new ArrayList<>();
    }
}
