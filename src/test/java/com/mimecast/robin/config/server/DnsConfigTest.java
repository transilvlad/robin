package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DnsConfigTest {

    @Test
    void defaultsFollowRfcSafeBehavior() {
        DnsConfig cfg = new DnsConfig(null);

        assertTrue(cfg.isMxImplicitFallbackEnabled());
        assertTrue(cfg.isMxNullMxHardFailEnabled());
        assertEquals("ipv4_first", cfg.getMxAddressFamilyPreference());
    }

    @Test
    void customRoutingOptionsAreParsed() {
        DnsConfig cfg = new DnsConfig(Map.of(
                "mxImplicitFallbackEnabled", false,
                "mxNullMxHardFailEnabled", false,
                "mxAddressFamilyPreference", "ipv6_first"
        ));

        assertFalse(cfg.isMxImplicitFallbackEnabled());
        assertFalse(cfg.isMxNullMxHardFailEnabled());
        assertEquals("ipv6_first", cfg.getMxAddressFamilyPreference());
    }
}
