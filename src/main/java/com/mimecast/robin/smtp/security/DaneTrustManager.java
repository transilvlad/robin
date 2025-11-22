package com.mimecast.robin.smtp.security;

import com.mimecast.robin.mx.dane.DaneRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.X509TrustManager;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/**
 * DANE-aware TrustManager for SMTP TLS certificate validation.
 * <p>Validates server certificates against DANE TLSA records per RFC 7672.
 * <p>Implements the following TLSA record types:
 * <ul>
 *   <li>Usage 2 (DANE-TA): Trust Anchor Assertion</li>
 *   <li>Usage 3 (DANE-EE): Domain-Issued Certificate (most common for SMTP)</li>
 * </ul>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - DANE TLSA</a>
 */
public class DaneTrustManager implements X509TrustManager {
    private static final Logger log = LogManager.getLogger(DaneTrustManager.class);

    private final SecurityPolicy securityPolicy;

    /**
     * Constructs a DANE TrustManager with the given security policy.
     *
     * @param securityPolicy SecurityPolicy containing DANE TLSA records.
     */
    public DaneTrustManager(SecurityPolicy securityPolicy) {
        if (securityPolicy == null || !securityPolicy.isDane()) {
            throw new IllegalArgumentException("DANE TrustManager requires DANE security policy");
        }
        this.securityPolicy = securityPolicy;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // Not used for SMTP client mode.
        throw new CertificateException("Client certificate validation not supported in DANE mode");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Certificate chain is empty");
        }

        List<DaneRecord> daneRecords = securityPolicy.getDaneRecords();
        if (daneRecords.isEmpty()) {
            throw new CertificateException("No DANE TLSA records available for validation");
        }

        log.info("Validating certificate against {} DANE TLSA records for: {}",
                daneRecords.size(), securityPolicy.getMxHostname());

        // Try to match certificate against each TLSA record.
        for (DaneRecord daneRecord : daneRecords) {
            try {
                if (validateCertificateAgainstTLSA(chain, daneRecord)) {
                    log.info("Certificate validated successfully against DANE TLSA record: {}",
                            daneRecord.getUsageDescription());
                    return; // Success!
                }
            } catch (Exception e) {
                log.debug("TLSA record validation failed: {}", e.getMessage());
            }
        }

        // No TLSA record matched.
        throw new CertificateException(
                "Certificate does not match any DANE TLSA records for " + securityPolicy.getMxHostname());
    }

    /**
     * Validates a certificate chain against a DANE TLSA record.
     *
     * @param chain      Certificate chain.
     * @param daneRecord TLSA record to validate against.
     * @return true if certificate matches TLSA record.
     * @throws Exception if validation fails.
     */
    private boolean validateCertificateAgainstTLSA(X509Certificate[] chain, DaneRecord daneRecord) throws Exception {
        int usage = daneRecord.getUsage();
        int selector = daneRecord.getSelector();
        int matchingType = daneRecord.getMatchingType();
        String expectedHash = daneRecord.getCertificateData().toLowerCase();

        log.debug("Validating against TLSA: usage={}, selector={}, matchingType={}",
                usage, selector, matchingType);

        // Determine which certificate to check based on usage.
        X509Certificate certToCheck;
        switch (usage) {
            case 0: // PKIX-TA (CA constraint) - check trust anchor.
            case 2: // DANE-TA (trust anchor assertion) - check trust anchor.
                certToCheck = chain[chain.length - 1]; // Root/trust anchor.
                break;

            case 1: // PKIX-EE (service certificate constraint) - check end entity.
            case 3: // DANE-EE (domain-issued certificate) - check end entity.
                certToCheck = chain[0]; // End entity certificate.
                break;

            default:
                log.warn("Unsupported TLSA usage: {}", usage);
                return false;
        }

        // Extract the data to hash based on selector.
        byte[] dataToHash;
        switch (selector) {
            case 0: // Full certificate.
                dataToHash = certToCheck.getEncoded();
                break;

            case 1: // SubjectPublicKeyInfo.
                dataToHash = certToCheck.getPublicKey().getEncoded();
                break;

            default:
                log.warn("Unsupported TLSA selector: {}", selector);
                return false;
        }

        // Apply matching type (hash algorithm).
        String actualHash;
        switch (matchingType) {
            case 0: // No hash (exact match).
                actualHash = HexFormat.of().formatHex(dataToHash).toLowerCase();
                break;

            case 1: // SHA-256.
                actualHash = hashData(dataToHash, "SHA-256");
                break;

            case 2: // SHA-512.
                actualHash = hashData(dataToHash, "SHA-512");
                break;

            default:
                log.warn("Unsupported TLSA matching type: {}", matchingType);
                return false;
        }

        boolean matches = expectedHash.equals(actualHash);
        if (matches) {
            log.info("Certificate matches TLSA record: usage={} ({}), selector={}, matchingType={}",
                    usage, daneRecord.getUsageDescription(), selector, matchingType);
        } else {
            log.debug("Certificate hash mismatch. Expected: {}, Got: {}", expectedHash, actualHash);
        }

        return matches;
    }

    /**
     * Hashes data using the specified algorithm.
     *
     * @param data      Data to hash.
     * @param algorithm Hash algorithm (SHA-256 or SHA-512).
     * @return Hex string of hash.
     * @throws Exception if hashing fails.
     */
    private String hashData(byte[] data, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] hash = digest.digest(data);
        return HexFormat.of().formatHex(hash).toLowerCase();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // Return empty array - DANE doesn't rely on pre-configured CAs.
        return new X509Certificate[0];
    }
}
