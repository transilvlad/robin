package com.mimecast.robin.mx.client;

import com.mimecast.robin.mx.assets.StsRecord;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import java.util.ArrayList;
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
}
