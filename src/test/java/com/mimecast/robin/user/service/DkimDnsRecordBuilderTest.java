package com.mimecast.robin.user.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimDnsRecordBuilderTest {

    @Test
    void rsaTxtValueChunksAreWithin255Characters() {
        DkimKeyGenerator keyGenerator = new DkimKeyGenerator();
        DkimDnsRecordBuilder builder = new DkimDnsRecordBuilder();
        DkimKeyGenerator.GeneratedKey generated = keyGenerator.generateRsa2048();

        DkimDnsRecordBuilder.DkimDnsRecord record = builder.buildRecord(
                "s20260227",
                "example.com",
                generated.algorithm(),
                generated.dnsPublicKey(),
                true
        );

        assertTrue(record.txtChunks().size() > 1);
        assertTrue(record.txtChunks().stream().allMatch(chunk -> chunk.length() <= DkimDnsRecordBuilder.MAX_DNS_TXT_CHUNK_SIZE));
        assertTrue(record.txtValue().contains("v=DKIM1"));
        assertTrue(record.txtValue().contains("k=rsa"));
        assertTrue(record.txtValue().contains("t=y"));
    }

    @Test
    void revocationTxtValueHasEmptyPublicKey() {
        DkimDnsRecordBuilder builder = new DkimDnsRecordBuilder();

        String revocation = builder.buildRevocationTxtValue("RSA_2048");

        assertEquals("v=DKIM1; k=rsa; p=", revocation);
    }
}
