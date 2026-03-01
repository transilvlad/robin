package com.mimecast.robin.user.endpoint.dto;

import java.util.List;

/**
 * DTO for DKIM DNS TXT record details.
 */
public record DkimDnsRecordDto(
        Long keyId,
        String name,
        String type,
        String value,
        List<String> chunks,
        String status
) {
}
