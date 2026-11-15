package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.assets.StsPolicy;
import com.mimecast.robin.mx.exception.*;
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
     * Why the last initialization failed, for the routing error.
     */
    private static volatile String initError = "not initialized";

    /**
     * Domain to query.
     */
    private final String domain;

    /**
     * StrictTransportSecurity used by this instance, or null when it could not be initialized.
     */
    private final StrictTransportSecurity sts;

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
        this.sts = strictTransportSecurity;
    }

    /**
     * Constructs a new StrictMx instance with the given StrictTransportSecurity.
     * <p>For testing; null simulates an initialization failure.
     *
     * @param domain Recipient domain.
     * @param sts    StrictTransportSecurity instance or null.
     */
    StrictMx(String domain, StrictTransportSecurity sts) {
        this.domain = domain;
        this.sts = sts;
    }

    /**
     * Initialize.
     * <p>Construct a new StrictTransportSecurity if needed. A failure is retried by the next instance.
     */
    private static synchronized void init() {
        if (strictTransportSecurity != null) {
            return;
        }
        try {
            System.setProperty("com.sun.net.ssl.checkRevocation", "true");
            Security.setProperty("ocsp.enable", "true");

            strictTransportSecurity = new StrictTransportSecurity();
        } catch (Exception e) {
            initError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("MTA-STS initialization error (check the server truststore setting): {}", initError);
        }
    }

    /**
     * Gets the StrictTransportSecurity instance.
     * <p>Without it the MTA-STS policy cannot be checked, so delivery must not proceed: an enforced
     * policy would be silently ignored. Fails as a transient routing error so the message is retried.
     *
     * @return StrictTransportSecurity instance.
     * @throws MailRoutingException When MTA-STS could not be initialized.
     */
    private StrictTransportSecurity sts() {
        if (sts == null) {
            throw new MailRoutingException("MTA-STS policy check unavailable for " + domain + ": " + initError, false);
        }
        return sts;
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
                Optional<StsPolicy> optional = sts().getPolicy(domain);

                if (optional.isPresent() && optional.get().isValid()) {
                    log.info("Policy loaded");
                    policy = optional.get();
                }

                if (policy != null && policy.getReport() != null && policy.getReport().isValid()) {
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
        List<DnsRecord> mxRecords = sts().getMxRecords(domain);
        log.debug("RAW records count: {}", mxRecords.size());

        if (getPolicy() != null) {
            List<DnsRecord> stsRecords = new ArrayList<>();
            for (DnsRecord mxRecord : mxRecords) {
                if (policy.matchMx(mxRecord.getValue())) {
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
