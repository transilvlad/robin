package com.mimecast.robin.smtp.security;

import com.mimecast.robin.mx.assets.DnsRecord;

/**
 * MX record with associated security policy.
 * <p>Combines an MX DNS record with the security policy (DANE/MTA-STS/Opportunistic)
 * <br>that applies to connections to that MX host.
 *
 * @see SecurityPolicy
 * @see DnsRecord
 */
public class SecureMxRecord {
    private final DnsRecord mxRecord;
    private final SecurityPolicy securityPolicy;

    /**
     * Constructs a SecureMxRecord.
     *
     * @param mxRecord       The MX DNS record.
     * @param securityPolicy The security policy for this MX.
     */
    public SecureMxRecord(DnsRecord mxRecord, SecurityPolicy securityPolicy) {
        if (mxRecord == null) {
            throw new IllegalArgumentException("MX record cannot be null");
        }
        if (securityPolicy == null) {
            throw new IllegalArgumentException("Security policy cannot be null");
        }
        this.mxRecord = mxRecord;
        this.securityPolicy = securityPolicy;
    }

    /**
     * Gets the MX DNS record.
     *
     * @return DnsRecord.
     */
    public DnsRecord getMxRecord() {
        return mxRecord;
    }

    /**
     * Gets the security policy.
     *
     * @return SecurityPolicy.
     */
    public SecurityPolicy getSecurityPolicy() {
        return securityPolicy;
    }

    /**
     * Gets the MX hostname.
     *
     * @return MX hostname string.
     */
    public String getHostname() {
        return mxRecord.getValue();
    }

    /**
     * Gets the MX priority.
     *
     * @return Priority value.
     */
    public int getPriority() {
        return mxRecord.getPriority();
    }

    @Override
    public String toString() {
        return String.format("SecureMxRecord{mx=%s, priority=%d, policy=%s}",
                getHostname(), getPriority(), securityPolicy.getType());
    }
}
