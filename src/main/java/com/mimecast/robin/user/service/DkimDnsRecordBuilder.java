package com.mimecast.robin.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds DKIM DNS TXT records and handles 255-byte chunking.
 */
public class DkimDnsRecordBuilder {

    public static final int MAX_DNS_TXT_CHUNK_SIZE = 255;

    /**
     * Builds a DKIM DNS record for a domain/selector.
     *
     * @param selector selector value
     * @param domain domain name
     * @param algorithm algorithm marker
     * @param dnsPublicKey p= value
     * @param testMode if true appends t=y
     * @return structured DNS record
     */
    public DkimDnsRecord buildRecord(String selector,
                                     String domain,
                                     String algorithm,
                                     String dnsPublicKey,
                                     boolean testMode) {
        String fqdn = selector + "._domainkey." + domain;
        String txtValue = buildTxtValue(algorithm, dnsPublicKey, testMode);
        List<String> chunks = chunkTxtValue(txtValue);
        String zoneValue = formatZoneChunks(chunks);
        return new DkimDnsRecord(fqdn, txtValue, chunks, zoneValue);
    }

    /**
     * Builds a revocation record with empty p=.
     *
     * @param algorithm algorithm marker
     * @return DKIM TXT value
     */
    public String buildRevocationTxtValue(String algorithm) {
        return buildTxtValue(algorithm, "", false);
    }

    /**
     * Builds DKIM TXT value payload.
     *
     * @param algorithm algorithm marker
     * @param dnsPublicKey p= value
     * @param testMode if true appends t=y
     * @return DKIM TXT value payload
     */
    public String buildTxtValue(String algorithm, String dnsPublicKey, boolean testMode) {
        Objects.requireNonNull(algorithm, "algorithm");
        String keyType = mapKeyType(algorithm);
        StringBuilder builder = new StringBuilder("v=DKIM1; k=")
                .append(keyType)
                .append("; p=")
                .append(Objects.requireNonNullElse(dnsPublicKey, ""));
        if (testMode) {
            builder.append("; t=y");
        }
        return builder.toString();
    }

    /**
     * Splits DNS TXT value into RFC-compliant chunk sizes.
     *
     * @param txtValue complete DKIM TXT value
     * @return chunks of max 255 chars
     */
    public List<String> chunkTxtValue(String txtValue) {
        Objects.requireNonNull(txtValue, "txtValue");
        List<String> chunks = new ArrayList<>();
        if (txtValue.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        for (int i = 0; i < txtValue.length(); i += MAX_DNS_TXT_CHUNK_SIZE) {
            int end = Math.min(i + MAX_DNS_TXT_CHUNK_SIZE, txtValue.length());
            chunks.add(txtValue.substring(i, end));
        }
        return chunks;
    }

    /**
     * Formats TXT chunks for zone-file style output.
     *
     * @param chunks TXT chunks
     * @return quoted chunk string
     */
    public String formatZoneChunks(List<String> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        return chunks.stream()
                .map(chunk -> "\"" + chunk + "\"")
                .collect(Collectors.joining(" "));
    }

    private static String mapKeyType(String algorithm) {
        String value = algorithm.toLowerCase(Locale.ROOT);
        if (value.contains("ed25519")) {
            return "ed25519";
        }
        return "rsa";
    }

    /**
     * Structured DKIM DNS record output.
     *
     * @param fqdn selector FQDN
     * @param txtValue unchunked TXT value
     * @param txtChunks split TXT chunks (<=255 chars)
     * @param zoneValue quoted zone-file string
     */
    public record DkimDnsRecord(
            String fqdn,
            String txtValue,
            List<String> txtChunks,
            String zoneValue
    ) {
    }
}
