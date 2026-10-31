package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.assets.StsPolicy;
import com.mimecast.robin.mx.assets.StsRecord;
import com.mimecast.robin.mx.assets.StsReport;
import com.mimecast.robin.mx.cache.MemoryPolicyCache;
import com.mimecast.robin.mx.client.DnsRecordClient;
import com.mimecast.robin.mx.client.HttpsPolicyClient;
import com.mimecast.robin.mx.client.OkHttpsResponse;
import com.mimecast.robin.mx.exception.BadPolicyException;
import com.mimecast.robin.mx.exception.BadRecordException;
import com.mimecast.robin.mx.exception.PolicyFetchErrorException;
import com.mimecast.robin.mx.exception.PolicyWebPKIInvalidException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StrictTransportSecurityRefreshTest {
    private static final String DOMAIN = "example.com";
    private static final String ENFORCE = "version: STSv1\r\nmode: enforce\r\nmx: mx.example.com\r\nmax_age: 604800\r\n";
    private final MemoryPolicyCache cache = new MemoryPolicyCache();
    private final FakeDns dns = new FakeDns();
    private final FakeHttps https = new FakeHttps();

    @Test
    void changedIdAndFailedHttpsOrPkixRetainThePreviouslyVerifiedPolicy() throws Exception {
        var sts = new StrictTransportSecurity(dns, https, cache);
        StsPolicy original = sts.getPolicy(DOMAIN).orElseThrow();
        dns.record = record("new");
        https.fail = true;
        assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
        assertSame(original, cache.getByDomain(DOMAIN).orElseThrow());
        https.fail = false;
        https.badPkix = true;
        assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
    }

    @Test
    void invalidDnsAndInvalidReplacementCannotDowngradeAnUnexpiredPolicy() throws Exception {
        var sts = new StrictTransportSecurity(dns, https, cache);
        StsPolicy original = sts.getPolicy(DOMAIN).orElseThrow();
        dns.record = new StsRecord(DOMAIN, "v=STSv1; id=;");
        assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
        dns.record = record("new");
        https.body = "version: STSv1\r\nmode: pretend\r\nmax_age: 0\r\n";
        assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
        dns.record = null;
        assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
    }

    @Test
    void verifiedReplacementAndExplicitWithdrawalSupersedeOldEnforcement() throws Exception {
        var sts = new StrictTransportSecurity(dns, https, cache);
        StsPolicy original = sts.getPolicy(DOMAIN).orElseThrow();
        dns.record = record("replacement");
        https.body = ENFORCE.replace("mx.example.com", "replacement.example.com");
        StsPolicy replacement = sts.getPolicy(DOMAIN).orElseThrow();
        assertNotSame(original, replacement);
        assertEquals("replacement", replacement.getRecord().getId());
        assertSame(replacement, cache.getByDomain(DOMAIN).orElseThrow());
        dns.record = record("withdrawal");
        https.body = "version: STSv1\r\nmode: none\r\nmax_age: 0\r\n";
        assertTrue(sts.getPolicy(DOMAIN).isEmpty());
        assertTrue(cache.getByDomain(DOMAIN).isEmpty());
    }

    @Test
    void malformedWithdrawalCannotDiscardEnforcement() throws Exception {
        var sts = new StrictTransportSecurity(dns, https, cache);
        StsPolicy original = sts.getPolicy(DOMAIN).orElseThrow();
        dns.record = record("withdrawal");
        for (String body : List.of("version: STSv1\r\nmode: none\r\nmax_age: bad\r\n",
                "version: STSv1\r\nmode: none\r\nmax_age: +0\r\n",
                "version: STSv1\r\nmode: none\r\nmode: enforce\r\nmax_age: 0\r\n",
                "version: STSv2\r\nmode: none\r\nmax_age: 0\r\n")) {
            https.body = body;
            assertSame(original, sts.getPolicy(DOMAIN).orElseThrow());
        }
    }

    @Test
    void policyMxNamesAreDnsNamesNotUnescapedRegularExpressions() throws Exception {
        var sts = new StrictTransportSecurity(dns, https, cache);
        var policy = sts.getPolicy(DOMAIN).orElseThrow();
        assertTrue(policy.matchMx("MX.EXAMPLE.COM."));
        assertFalse(policy.matchMx("mxXexampleYcom"));
        dns.record = record("wildcard");
        https.body = ENFORCE.replace("mx.example.com", "*.example.com");
        policy = sts.getPolicy(DOMAIN).orElseThrow();
        assertTrue(policy.matchMx("mx.example.com"));
        assertFalse(policy.matchMx("example.com"));
        assertFalse(policy.matchMx("example.com.attacker.net"));
        assertFalse(policy.matchMx("mxXexampleYcom"));
    }

    @Test
    void expiredPolicyIsNotReturnedAndFailureWithoutPriorPolicyStillSurfaces() throws Exception {
        StsPolicy expired = new StsPolicy(ENFORCE + "domain: " + DOMAIN
                + "\r\nrecord_id: old\r\nfetch_time: 1\r\n").make();
        cache.put(expired);
        assertTrue(cache.getByDomain(DOMAIN).isEmpty());
        var sts = new StrictTransportSecurity(dns, https, cache);
        https.fail = true;
        assertThrows(PolicyFetchErrorException.class, () -> sts.getPolicy(DOMAIN));
        https.fail = false;
        https.body = "invalid\r\n";
        assertThrows(BadPolicyException.class, () -> sts.getPolicy(DOMAIN));
        dns.record = new StsRecord(DOMAIN, "v=STSv1; id=;");
        assertThrows(BadRecordException.class, () -> sts.getPolicy(DOMAIN));
    }

    private static StsRecord record(String id) {
        return new StsRecord(DOMAIN, "v=STSv1; id=" + id + ";");
    }

    private static final class FakeDns implements DnsRecordClient {
        private StsRecord record = record("old");
        @Override public Optional<StsRecord> getStsRecord(String domain) { return Optional.ofNullable(record); }
        @Override public Optional<StsReport> getRptRecord(String domain) { return Optional.empty(); }
        @Override public Optional<String> getPtrRecord(String ip) { return Optional.empty(); }
        @Override public Optional<List<DnsRecord>> getMxRecords(String domain) { return Optional.empty(); }
    }

    private static final class FakeHttps implements HttpsPolicyClient {
        private String body = ENFORCE;
        private boolean fail;
        private boolean badPkix;
        @Override
        public OkHttpsResponse getPolicy(StsRecord record, int limit)
                throws PolicyFetchErrorException, PolicyWebPKIInvalidException {
            if (fail) throw new PolicyFetchErrorException("Synthetic HTTPS outage");
            if (badPkix) throw new PolicyWebPKIInvalidException("Synthetic invalid certificate");
            return new OkHttpsResponse(null, limit) {
                @Override public String getBody() { return body; }
                @Override public boolean isSuccessful() { return true; }
                @Override public int getCode() { return 200; }
                @Override public boolean isHandshake() { return true; }
                @Override public String getHeader(String name) { return "text/plain"; }
            };
        }
    }
}
