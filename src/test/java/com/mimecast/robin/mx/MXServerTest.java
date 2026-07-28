package com.mimecast.robin.mx;

import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class MXServerTest {

    @BeforeAll
    static void before() {
        Lookup.setDefaultResolver(new LocalDnsResolver());
        LocalDnsResolver.put("bounce.example", Type.CNAME, new ArrayList<>() {{
            add("rp.example.");
        }});
        LocalDnsResolver.put("rp.example", Type.A, new ArrayList<>() {{
            add("192.0.2.44");
        }});
    }

    @Test
    void resolvesCnameAliasToAddress() {
        MXServer server = new MXServer("bounce.example", 0);
        List<String> ips = server.getIpAddresses();
        assertTrue(ips.contains("192.0.2.44"));
    }
}
