package com.mimecast.robin.user.endpoint.dto;

/**
 * Response DTO for managed DKIM keys.
 *
 * <p>Intentionally excludes private key material.
 */
public record DkimKeyDto(
        Long id,
        String domain,
        String selector,
        String algorithm,
        String status,
        boolean testMode,
        String strategy,
        String serviceTag,
        Long pairedKeyId,
        String publicKey,
        String rotationScheduledAt,
        String publishedAt,
        String activatedAt,
        String retireAfter,
        String retiredAt,
        String createdAt,
        DkimDnsRecordDto dnsRecord
) {
}
