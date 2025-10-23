package com.mimecast.robin.mx.rbl;

import com.mimecast.robin.mx.util.LocalDnsResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBL Checker Test.
 * <p>Tests for the RBL Checker utility.
 */
class RblCheckerTest {

    private static final String CLEAN_IP = "192.168.1.1";
    private static final String LISTED_IP = "10.0.0.1";
    private static final String INVALID_IP = "999.999.999.999";

    /**
     * Set up test environment with mock DNS resolver.
     */
    @BeforeAll
    static void before() {
        // Set local DNS resolver
        Lookup.setDefaultResolver(new LocalDnsResolver());

        // Set up mock responses for the test RBL domains

        // Listed IP in test-rbl-1.example.com
        LocalDnsResolver.put("1.0.0.10.test-rbl-1.example.com", Type.A, new ArrayList<String>() {{
            add("127.0.0.2");
        }});

        // Listed IP in test-rbl-2.example.com with multiple responses
        LocalDnsResolver.put("1.0.0.10.test-rbl-2.example.com", Type.A, new ArrayList<String>() {{
            add("127.0.0.2");
            add("127.0.0.3");
        }});

        // Clean IP should return no records for all RBLs
    }

    /**
     * Test reverse IP functionality with valid IP.
     */
    @Test
    void testReverseIp() {
        assertEquals("1.1.168.192", RblChecker.reverseIp("192.168.1.1"));
        assertEquals("1.0.0.10", RblChecker.reverseIp("10.0.0.1"));
        assertEquals("1.1.1.127", RblChecker.reverseIp("127.1.1.1"));
    }

    /**
     * Test reverse IP functionality with invalid IP.
     */
    @Test
    void testReverseIpWithInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> RblChecker.reverseIp("invalid"));
        assertThrows(IllegalArgumentException.class, () -> RblChecker.reverseIp("999.999.999.999"));
        assertThrows(IllegalArgumentException.class, () -> RblChecker.reverseIp(""));
        assertThrows(IllegalArgumentException.class, () -> RblChecker.reverseIp(null));
    }

    /**
     * Test IP validation functionality.
     */
    @Test
    void testIsValidIp() {
        assertTrue(RblChecker.isValidIp("192.168.1.1"));
        assertTrue(RblChecker.isValidIp("10.0.0.1"));
        assertTrue(RblChecker.isValidIp("127.0.0.1"));

        assertFalse(RblChecker.isValidIp("invalid"));
        assertFalse(RblChecker.isValidIp("999.999.999.999"));
        assertFalse(RblChecker.isValidIp(""));
        assertFalse(RblChecker.isValidIp(null));
    }

    /**
     * Test checking a clean IP against a single RBL.
     */
    @Test
    void testCheckCleanIpAgainstSingleRbl() {
        RblResult result = RblChecker.checkIpAgainstRbl(CLEAN_IP, "test-rbl-1.example.com");

        assertNotNull(result);
        assertEquals(CLEAN_IP, result.getIp());
        assertEquals("test-rbl-1.example.com", result.getRblProvider());
        assertFalse(result.isListed());
        assertTrue(result.getResponseRecords().isEmpty());
    }

    /**
     * Test checking a listed IP against a single RBL.
     */
    @Test
    void testCheckListedIpAgainstSingleRbl() {
        RblResult result = RblChecker.checkIpAgainstRbl(LISTED_IP, "test-rbl-1.example.com");

        assertNotNull(result);
        assertEquals(LISTED_IP, result.getIp());
        assertEquals("test-rbl-1.example.com", result.getRblProvider());
        assertTrue(result.isListed());
        assertEquals(1, result.getResponseRecords().size());
        assertEquals("127.0.0.2", result.getResponseRecords().get(0));
    }

    /**
     * Test checking an invalid IP against a single RBL.
     */
    @Test
    void testCheckInvalidIpAgainstSingleRbl() {
        RblResult result = RblChecker.checkIpAgainstRbl(INVALID_IP, "test-rbl-1.example.com");

        assertNotNull(result);
        assertEquals(INVALID_IP, result.getIp());
        assertEquals("test-rbl-1.example.com", result.getRblProvider());
        assertFalse(result.isListed());
        assertTrue(result.getResponseRecords().isEmpty());
    }

    /**
     * Test checking an IP against multiple RBLs.
     */
    @Test
    void testCheckIpAgainstMultipleRbls() {
        List<String> rblProviders = Arrays.asList(
                "test-rbl-1.example.com",
                "test-rbl-2.example.com",
                "test-rbl-3.example.com"
        );

        List<RblResult> results = RblChecker.checkIpAgainstRbls(LISTED_IP, rblProviders);

        assertNotNull(results);
        assertEquals(3, results.size());

        // Check individual results
        RblResult result1 = findResultByProvider(results, "test-rbl-1.example.com");
        assertTrue(result1.isListed());
        assertEquals(1, result1.getResponseRecords().size());

        RblResult result2 = findResultByProvider(results, "test-rbl-2.example.com");
        assertTrue(result2.isListed());
        assertEquals(2, result2.getResponseRecords().size());

        RblResult result3 = findResultByProvider(results, "test-rbl-3.example.com");
        assertFalse(result3.isListed());
        assertTrue(result3.getResponseRecords().isEmpty());
    }

    /**
     * Test handling of empty or null input for multiple RBL check.
     */
    @Test
    void testCheckIpAgainstRblsWithInvalidInput() {
        // Test with null IP
        assertTrue(RblChecker.checkIpAgainstRbls(null, Arrays.asList("test-rbl-1.example.com")).isEmpty());

        // Test with empty IP
        assertTrue(RblChecker.checkIpAgainstRbls("", Arrays.asList("test-rbl-1.example.com")).isEmpty());

        // Test with null RBL list
        assertTrue(RblChecker.checkIpAgainstRbls(CLEAN_IP, null).isEmpty());

        // Test with empty RBL list
        assertTrue(RblChecker.checkIpAgainstRbls(CLEAN_IP, Arrays.asList()).isEmpty());
    }

    /**
     * Test timeout functionality.
     * This is hard to test properly without mocking the DNS lookups with delays,
     * but we can at least verify the method executes without exceptions.
     */
    @Test
    void testCheckIpAgainstRblsWithTimeout() {
        List<String> rblProviders = Arrays.asList(
                "test-rbl-1.example.com",
                "test-rbl-2.example.com"
        );

        List<RblResult> results = RblChecker.checkIpAgainstRbls(LISTED_IP, rblProviders, 1);

        assertNotNull(results);
        assertEquals(2, results.size());
    }

    /**
     * Helper method to find a result by RBL provider name.
     */
    private RblResult findResultByProvider(List<RblResult> results, String providerName) {
        return results.stream()
                .filter(r -> r.getRblProvider().equals(providerName))
                .findFirst()
                .orElse(null);
    }
}
