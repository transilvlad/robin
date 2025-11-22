package com.mimecast.robin.mx.dane;

/**
 * Represents a DANE TLSA record for SMTP.
 * <p>TLSA records provide certificate association data for TLS authentication.
 * <p>Format: _port._protocol.hostname IN TLSA usage selector matching data
 *
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - DANE</a>
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 */
public class DaneRecord {
    private final String hostname;
    private final int usage;
    private final int selector;
    private final int matchingType;
    private final String certificateData;
    private final String tlsaRecord;

    /**
     * Constructor.
     *
     * @param hostname         The MX hostname this TLSA record applies to.
     * @param usage            Certificate usage (0-3).
     * @param selector         Selector (0=full cert, 1=subject public key info).
     * @param matchingType     Matching type (0=exact, 1=SHA-256, 2=SHA-512).
     * @param certificateData  The certificate association data (hex string).
     * @param tlsaRecord       The full TLSA record string.
     */
    public DaneRecord(String hostname, int usage, int selector, int matchingType,
                      String certificateData, String tlsaRecord) {
        this.hostname = hostname;
        this.usage = usage;
        this.selector = selector;
        this.matchingType = matchingType;
        this.certificateData = certificateData;
        this.tlsaRecord = tlsaRecord;
    }

    /**
     * Get the hostname this TLSA record applies to.
     *
     * @return Hostname.
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Get the certificate usage field.
     * <ul>
     *   <li>0 = CA constraint (PKIX-TA)</li>
     *   <li>1 = Service certificate constraint (PKIX-EE)</li>
     *   <li>2 = Trust anchor assertion (DANE-TA)</li>
     *   <li>3 = Domain-issued certificate (DANE-EE)</li>
     * </ul>
     *
     * @return Usage value (0-3).
     */
    public int getUsage() {
        return usage;
    }

    /**
     * Get the selector field.
     * <ul>
     *   <li>0 = Full certificate</li>
     *   <li>1 = SubjectPublicKeyInfo</li>
     * </ul>
     *
     * @return Selector value (0-1).
     */
    public int getSelector() {
        return selector;
    }

    /**
     * Get the matching type field.
     * <ul>
     *   <li>0 = No hash used (exact match)</li>
     *   <li>1 = SHA-256 hash</li>
     *   <li>2 = SHA-512 hash</li>
     * </ul>
     *
     * @return Matching type value (0-2).
     */
    public int getMatchingType() {
        return matchingType;
    }

    /**
     * Get the certificate association data (hex string).
     *
     * @return Certificate data.
     */
    public String getCertificateData() {
        return certificateData;
    }

    /**
     * Get the full TLSA record string.
     *
     * @return TLSA record.
     */
    public String getTlsaRecord() {
        return tlsaRecord;
    }

    /**
     * Get human-readable usage description.
     *
     * @return Usage description.
     */
    public String getUsageDescription() {
        return switch (usage) {
            case 0 -> "PKIX-TA (CA constraint)";
            case 1 -> "PKIX-EE (Service certificate constraint)";
            case 2 -> "DANE-TA (Trust anchor assertion)";
            case 3 -> "DANE-EE (Domain-issued certificate)";
            default -> "Unknown usage: " + usage;
        };
    }

    /**
     * Get human-readable selector description.
     *
     * @return Selector description.
     */
    public String getSelectorDescription() {
        return switch (selector) {
            case 0 -> "Full certificate";
            case 1 -> "SubjectPublicKeyInfo";
            default -> "Unknown selector: " + selector;
        };
    }

    /**
     * Get human-readable matching type description.
     *
     * @return Matching type description.
     */
    public String getMatchingTypeDescription() {
        return switch (matchingType) {
            case 0 -> "No hash (exact match)";
            case 1 -> "SHA-256";
            case 2 -> "SHA-512";
            default -> "Unknown matching type: " + matchingType;
        };
    }

    @Override
    public String toString() {
        return String.format("TLSA %d %d %d %s", usage, selector, matchingType, certificateData);
    }
}
