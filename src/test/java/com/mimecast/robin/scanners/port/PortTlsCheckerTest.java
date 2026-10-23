package com.mimecast.robin.scanners.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PortTlsChecker hostname matching and PortTlsResult key strength.
 */
class PortTlsCheckerTest {

    @Test
    void hostnameMatchesExactName() {
        assertTrue(PortTlsChecker.hostnameMatches("mx.example.com", List.of("mx.example.com")));
        assertTrue(PortTlsChecker.hostnameMatches("MX.Example.com.", List.of("mx.example.com")));
        assertFalse(PortTlsChecker.hostnameMatches("mx.example.com", List.of("mail.example.com")));
    }

    @Test
    void hostnameMatchesWildcardOneLabelOnly() {
        List<String> names = List.of("*.example.com");
        assertTrue(PortTlsChecker.hostnameMatches("mx.example.com", names));
        assertFalse(PortTlsChecker.hostnameMatches("a.mx.example.com", names));
        assertFalse(PortTlsChecker.hostnameMatches("example.com", names));
    }

    @Test
    void hostnameMatchesAnyListedName() {
        assertTrue(PortTlsChecker.hostnameMatches("mx2.example.com", List.of("mx1.example.com", "mx2.example.com")));
    }

    @Test
    void hostnameDoesNotMatchWithoutNames() {
        assertFalse(PortTlsChecker.hostnameMatches("mx.example.com", List.of()));
        assertFalse(PortTlsChecker.hostnameMatches("", List.of("mx.example.com")));
        assertFalse(PortTlsChecker.hostnameMatches(null, List.of("mx.example.com")));
    }

    @Test
    void certKeyWeakDependsOnAlgorithm() {
        assertTrue(result("RSA", 1024).isCertKeyWeak());
        assertFalse(result("RSA", 2048).isCertKeyWeak());
        assertTrue(result("EC", 224).isCertKeyWeak());
        assertFalse(result("EC", 256).isCertKeyWeak());
        assertFalse(result("EC", 384).isCertKeyWeak());
        assertFalse(result("Ed25519", 256).isCertKeyWeak());
        assertFalse(result(null, 0).isCertKeyWeak());
    }

    private static PortTlsResult result(String algorithm, int bits) {
        return PortTlsResult.builder("mx.example.com", 25)
                .certKeyAlgorithm(algorithm)
                .certKeyBits(bits)
                .build();
    }
}
