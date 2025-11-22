package com.mimecast.robin.smtp.security;

import com.mimecast.robin.mx.dane.DaneRecord;

import java.util.Collections;
import java.util.List;

/**
 * Security policy for SMTP connections.
 * <p>Represents the security requirements for an SMTP connection based on
 * DANE (RFC 7672) or MTA-STS (RFC 8461) policies discovered during MX resolution.
 *
 * <p><strong>Priority (RFC 8461 Section 2):</strong>
 * <ol>
 *   <li>DANE - If TLSA records exist, DANE policy applies (MTA-STS is ignored)</li>
 *   <li>MTA-STS - If no DANE and MTA-STS policy exists, MTA-STS applies</li>
 *   <li>OPPORTUNISTIC - No security policy, TLS is opportunistic</li>
 * </ol>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 */
public class SecurityPolicy {

    /**
     * Security policy type.
     */
    public enum PolicyType {
        /**
         * DANE policy active - TLSA records found.
         * <p>Requirements (RFC 7672):
         * <ul>
         *   <li>TLS is MANDATORY</li>
         *   <li>Certificate MUST validate against TLSA records</li>
         *   <li>Failure results in message delay/bounce (no fallback to cleartext)</li>
         * </ul>
         */
        DANE,

        /**
         * MTA-STS policy active.
         * <p>Requirements (RFC 8461):
         * <ul>
         *   <li>TLS is MANDATORY</li>
         *   <li>Certificate MUST validate via PKI</li>
         *   <li>MX hostname MUST match policy</li>
         *   <li>Failure results in message delay/bounce (no fallback to cleartext)</li>
         * </ul>
         */
        MTA_STS,

        /**
         * No security policy - opportunistic TLS.
         * <p>TLS is attempted if advertised, but cleartext is acceptable.
         */
        OPPORTUNISTIC
    }

    private final PolicyType type;
    private final String mxHostname;
    private final List<DaneRecord> daneRecords;
    private final String mtaStsPolicy;

    /**
     * Private constructor - use factory methods.
     */
    private SecurityPolicy(PolicyType type, String mxHostname, List<DaneRecord> daneRecords, String mtaStsPolicy) {
        this.type = type;
        this.mxHostname = mxHostname;
        this.daneRecords = daneRecords != null ? List.copyOf(daneRecords) : Collections.emptyList();
        this.mtaStsPolicy = mtaStsPolicy;
    }

    /**
     * Creates a DANE security policy.
     *
     * @param mxHostname  MX hostname.
     * @param daneRecords TLSA records for this MX.
     * @return SecurityPolicy with DANE enforcement.
     */
    public static SecurityPolicy dane(String mxHostname, List<DaneRecord> daneRecords) {
        if (daneRecords == null || daneRecords.isEmpty()) {
            throw new IllegalArgumentException("DANE policy requires at least one TLSA record");
        }
        return new SecurityPolicy(PolicyType.DANE, mxHostname, daneRecords, null);
    }

    /**
     * Creates an MTA-STS security policy.
     *
     * @param mxHostname    MX hostname.
     * @param stsPolicyMode MTA-STS policy mode (enforce/testing).
     * @return SecurityPolicy with MTA-STS enforcement.
     */
    public static SecurityPolicy mtaSts(String mxHostname, String stsPolicyMode) {
        if (stsPolicyMode == null || stsPolicyMode.isEmpty()) {
            throw new IllegalArgumentException("MTA-STS policy requires policy mode");
        }
        return new SecurityPolicy(PolicyType.MTA_STS, mxHostname, null, stsPolicyMode);
    }

    /**
     * Creates an opportunistic security policy (no DANE or MTA-STS).
     *
     * @param mxHostname MX hostname.
     * @return SecurityPolicy with no mandatory security.
     */
    public static SecurityPolicy opportunistic(String mxHostname) {
        return new SecurityPolicy(PolicyType.OPPORTUNISTIC, mxHostname, null, null);
    }

    /**
     * Gets the policy type.
     *
     * @return PolicyType (DANE, MTA_STS, or OPPORTUNISTIC).
     */
    public PolicyType getType() {
        return type;
    }

    /**
     * Gets the MX hostname this policy applies to.
     *
     * @return MX hostname.
     */
    public String getMxHostname() {
        return mxHostname;
    }

    /**
     * Gets DANE TLSA records (only if type is DANE).
     *
     * @return List of DaneRecord, or empty list if not DANE policy.
     */
    public List<DaneRecord> getDaneRecords() {
        return daneRecords;
    }

    /**
     * Gets MTA-STS policy mode (only if type is MTA_STS).
     *
     * @return Policy mode string, or null if not MTA-STS policy.
     */
    public String getMtaStsPolicy() {
        return mtaStsPolicy;
    }

    /**
     * Checks if TLS is mandatory for this policy.
     * <p>TLS is mandatory for DANE and MTA-STS policies.
     *
     * @return true if TLS is required, false for opportunistic.
     */
    public boolean isTlsMandatory() {
        return type == PolicyType.DANE || type == PolicyType.MTA_STS;
    }

    /**
     * Checks if this is a DANE policy.
     *
     * @return true if DANE policy is active.
     */
    public boolean isDane() {
        return type == PolicyType.DANE;
    }

    /**
     * Checks if this is an MTA-STS policy.
     *
     * @return true if MTA-STS policy is active.
     */
    public boolean isMtaSts() {
        return type == PolicyType.MTA_STS;
    }

    /**
     * Checks if this is opportunistic (no security policy).
     *
     * @return true if no mandatory security policy.
     */
    public boolean isOpportunistic() {
        return type == PolicyType.OPPORTUNISTIC;
    }

    @Override
    public String toString() {
        return String.format("SecurityPolicy{type=%s, mxHostname='%s', daneRecords=%d, mtaStsPolicy='%s'}",
                type, mxHostname, daneRecords.size(), mtaStsPolicy);
    }
}
