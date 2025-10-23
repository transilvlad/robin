package com.mimecast.robin.util;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for VaultMagicProvider.
 */
@ExtendWith(VaultClientMockExtension.class)
class VaultMagicProviderTest {

    private VaultClient vaultClient;
    private Session session;

    @BeforeEach
    void setUp() {
        // Create VaultClient for testing
        vaultClient = new VaultClient.Builder()
                .withAddress("http://localhost:8200")
                .withToken("test-token")
                .build();

        VaultMagicProvider.initialize(vaultClient);
        session = new Session();
    }

    @AfterEach
    void tearDown() {
        VaultMagicProvider.clearCache();
    }

    @Test
    void testInitialization() {
        assertTrue(VaultMagicProvider.isEnabled());
    }

    @Test
    void testGetSecretNotFound() {
        String secret = VaultMagicProvider.getSecret("nonexistent");
        assertNull(secret);
    }

    @Test
    void testNullClientInitialization() {
        VaultMagicProvider.initialize(null);
        // Should not throw exception, just log warning
    }
}

