package com.mimecast.robin.sasl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlAuthProvider password verification logic.
 */
class SqlAuthProviderTest {

    @Test
    void verifyPassword_plain_noScheme() {
        assertTrue(SqlAuthProvider.verifyPassword("secret", "secret"));
        assertFalse(SqlAuthProvider.verifyPassword("wrong", "secret"));
    }

    @Test
    void verifyPassword_plainScheme() {
        assertTrue(SqlAuthProvider.verifyPassword("secret", "{PLAIN}secret"));
        assertFalse(SqlAuthProvider.verifyPassword("wrong", "{PLAIN}secret"));
    }

    @Test
    void verifyPassword_sha512Crypt() {
        // Generate a known SHA-512 crypt hash using commons-codec.
        String hash = org.apache.commons.codec.digest.Crypt.crypt("testpass", "$6$rounds=5000$saltsalt$");
        String stored = "{SHA512-CRYPT}" + hash;

        assertTrue(SqlAuthProvider.verifyPassword("testpass", stored));
        assertFalse(SqlAuthProvider.verifyPassword("wrongpass", stored));
    }

    @Test
    void verifyPassword_sha512Crypt_realWorldFormat() {
        // Typical Dovecot SHA512-CRYPT format with rounds parameter.
        String hash = org.apache.commons.codec.digest.Crypt.crypt("W*YM08", "$6$rounds=1000$testsalt$");
        String stored = "{SHA512-CRYPT}" + hash;

        assertTrue(SqlAuthProvider.verifyPassword("W*YM08", stored));
        assertFalse(SqlAuthProvider.verifyPassword("wrong", stored));
    }

    @Test
    void verifyPassword_nullAndEmpty() {
        assertFalse(SqlAuthProvider.verifyPassword("pass", null));
        assertFalse(SqlAuthProvider.verifyPassword("pass", ""));
    }

    @Test
    void verifyPassword_unsupportedScheme() {
        assertFalse(SqlAuthProvider.verifyPassword("pass", "{SSHA256}somehash"));
    }

    @Test
    void verifyPassword_caseInsensitiveScheme() {
        assertTrue(SqlAuthProvider.verifyPassword("secret", "{plain}secret"));
        assertTrue(SqlAuthProvider.verifyPassword("secret", "{Plain}secret"));
    }

    @Test
    void countPlaceholders_defaultQuery() {
        // The default query has 2 placeholders.
        assertEquals(2, countPlaceholders(SqlAuthProvider.DEFAULT_AUTH_QUERY));
    }

    @Test
    void countPlaceholders_simpleQuery() {
        assertEquals(1, countPlaceholders("SELECT password FROM users WHERE email = ?"));
    }

    @Test
    void countPlaceholders_noPlaceholders() {
        assertEquals(0, countPlaceholders("SELECT 1"));
    }

    /**
     * Helper to test the placeholder counting logic (same algorithm as SqlAuthProvider).
     */
    private static int countPlaceholders(String query) {
        int count = 0;
        for (char c : query.toCharArray()) {
            if (c == '?') count++;
        }
        return count;
    }
}
