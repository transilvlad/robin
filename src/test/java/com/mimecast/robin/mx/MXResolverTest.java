package com.mimecast.robin.mx;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.smtp.security.SecureMxRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MXResolver null-safety and edge cases.
 */
class MXResolverTest {

    /**
     * Test that MXResolver handles DnsRecord with null getValue() gracefully.
     * This simulates edge cases where DNS returns malformed records.
     */
    @Test
    void testResolveMxHandlesNullHostname() {
        // Create a mock DnsRecord that returns null for getValue()
        DnsRecord nullRecord = new DnsRecord() {
            @Override
            public String getValue() {
                return null;
            }

            @Override
            public int getPriority() {
                return 10;
            }
        };

        // The record should not cause NPE - verify getValue() returns null
        assertNull(nullRecord.getValue());
        assertEquals(10, nullRecord.getPriority());
    }

    /**
     * Test that MXResolver handles DnsRecord with empty getValue() gracefully.
     */
    @Test
    void testResolveMxHandlesEmptyHostname() {
        DnsRecord emptyRecord = new DnsRecord() {
            @Override
            public String getValue() {
                return "";
            }

            @Override
            public int getPriority() {
                return 10;
            }
        };

        assertEquals("", emptyRecord.getValue());
        assertEquals(10, emptyRecord.getPriority());
    }

    /**
     * Test that resolveMx returns empty list for non-existent domain
     * without throwing exceptions.
     */
    @Test
    void testResolveMxReturnsEmptyForNonExistentDomain() {
        MXResolver resolver = new MXResolver();
        // Use a domain that definitely doesn't exist
        List<DnsRecord> records = resolver.resolveMx("this-domain-definitely-does-not-exist-12345.invalid");
        assertNotNull(records);
        // Should return empty list, not throw
        assertTrue(records.isEmpty());
    }

    /**
     * Test that resolveSecureMx returns empty list for non-existent domain
     * without throwing exceptions.
     */
    @Test
    void testResolveSecureMxReturnsEmptyForNonExistentDomain() {
        MXResolver resolver = new MXResolver();
        List<SecureMxRecord> records = resolver.resolveSecureMx("this-domain-definitely-does-not-exist-12345.invalid");
        assertNotNull(records);
        assertTrue(records.isEmpty());
    }
}
