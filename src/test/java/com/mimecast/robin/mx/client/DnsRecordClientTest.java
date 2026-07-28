package com.mimecast.robin.mx.client;

import com.mimecast.robin.mx.assets.StsRecord;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("OptionalGetWithoutIsPresent")
class DnsRecordClientTest {

    @BeforeAll
    static void before() {
        // Set local resolver
        Lookup.setDefaultResolver(new LocalDnsResolver());
        LocalDnsResolver.put("_mta-sts.mimecast.com", Type.TXT, new ArrayList<>() {{
            add("v=STSv1; id=19840507T234501;");
        }});
        LocalDnsResolver.put("_mta-sts.mimecast.eu", Type.TXT, new ArrayList<>() {{
            add("v=STSv1; id=;");
        }});
        LocalDnsResolver.put("_mta-sts.mimecast.us", Type.TXT, new ArrayList<>() {{
            add("id=19840507T234501;");
        }});
        // No MX, but has A fallback.
        LocalDnsResolver.put("fallback.example", Type.A, new ArrayList<>() {{
            add("192.0.2.10");
        }});
        // No MX, only AAAA fallback.
        LocalDnsResolver.put("v6only.example", Type.AAAA, new ArrayList<>() {{
            add("2001:db8::10");
        }});
        // No MX, CNAME to A record.
        LocalDnsResolver.put("cname-fallback.example", Type.CNAME, new ArrayList<>() {{
            add("target-fallback.example.");
        }});
        LocalDnsResolver.put("target-fallback.example", Type.A, new ArrayList<>() {{
            add("192.0.2.20");
        }});
        // Null MX domain (RFC 7505).
        LocalDnsResolver.put("nullmx.example", Type.MX, new ArrayList<>() {{
            add("0 .");
        }});
        // PTR for loopback
        LocalDnsResolver.put("1.0.0.127.in-addr.arpa", Type.PTR, new ArrayList<>() {{
            add("localhost.");
        }});
    }

    @Test
    void getPtr() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        assertTrue(dnsRecordClient.getPtrRecord("127.0.0.1").isPresent());
    }

    @Test
    void getRecord() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        StsRecord record = dnsRecordClient.getStsRecord("mimecast.com").get();

        assertEquals("v=STSv1; id=19840507T234501;", record.toString());
    }

    @Test
    void getInvalid() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<StsRecord> optional = dnsRecordClient.getStsRecord("mimecast.eu");

        assertFalse(optional.get().isValid());
    }

    @Test
    void getSkipped() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<StsRecord> optional = dnsRecordClient.getStsRecord("mimecast.us");

        assertFalse(optional.isPresent());
    }

    @Test
    void getMalformed() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<StsRecord> optional = dnsRecordClient.getStsRecord(".eu");

        assertFalse(optional.isPresent());
    }

    @Test
    void getEmpty() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<StsRecord> optional = dnsRecordClient.getStsRecord("mimecast.net");

        assertFalse(optional.isPresent());
    }

    @Test
    void getMxRecordsImplicitFallbackUsesDomainTarget() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<List<com.mimecast.robin.mx.assets.DnsRecord>> records = dnsRecordClient.getMxRecords("fallback.example");

        assertTrue(records.isPresent());
        assertEquals(1, records.get().size());
        assertEquals("fallback.example", records.get().getFirst().getValue());
        assertEquals(0, records.get().getFirst().getPriority());
    }

    @Test
    void getMxRecordsImplicitFallbackViaCname() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<List<com.mimecast.robin.mx.assets.DnsRecord>> records = dnsRecordClient.getMxRecords("cname-fallback.example");

        assertTrue(records.isPresent());
        assertEquals(1, records.get().size());
        assertEquals("cname-fallback.example", records.get().getFirst().getValue());
        assertEquals(0, records.get().getFirst().getPriority());
    }

    @Test
    void getARecordsIncludesAaaa() {
        XBillDnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<List<com.mimecast.robin.mx.assets.DnsRecord>> records = dnsRecordClient.getARecords("v6only.example");

        assertTrue(records.isPresent());
        assertEquals("2001:db8:0:0:0:0:0:10", records.get().getFirst().getValue());
    }

    @Test
    void getMxRecordsNullMxReturnsEmpty() {
        DnsRecordClient dnsRecordClient = new XBillDnsRecordClient();
        Optional<List<com.mimecast.robin.mx.assets.DnsRecord>> records = dnsRecordClient.getMxRecords("nullmx.example");

        assertFalse(records.isPresent());
    }
}
