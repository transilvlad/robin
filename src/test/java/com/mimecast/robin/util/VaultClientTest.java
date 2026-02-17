package com.mimecast.robin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VaultClient utility.
 * <p>These tests use a mock server that needs to be isolated to avoid
 * port conflicts, so they run serially.
 */
@ExtendWith(VaultClientMockExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class VaultClientTest {

    // Helper method to get the mock server port
    private static int getMockServerPort() {
        // The MockWebServer in VaultClientMockExtension will be started on a dynamic port
        // We'll use a ThreadLocal to pass the port from the extension
        Integer port = VaultClientMockExtension.getMockServerPort();
        return port != null ? port : 8200;
    }

    @Test
    void testGetSecretKvV2() throws VaultClient.VaultException {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        String secret = client.getSecret("secret/data/myapp/config", "password");

        assertEquals("superSecretPassword123", secret);
    }

    @Test
    void testGetSecretKvV1() throws VaultClient.VaultException {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        String secret = client.getSecret("secret/database", "username");

        assertEquals("dbuser", secret);
    }

    @Test
    void testGetSecretNotFound() throws VaultClient.VaultException {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        String secret = client.getSecret("secret/data/myapp/config", "nonexistent");

        assertNull(secret);
    }

    @Test
    void testGetAllSecrets() throws VaultClient.VaultException {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        Map<String, String> secrets = client.getAllSecrets("secret/data/myapp/config");

        assertNotNull(secrets);
        assertEquals(3, secrets.size());
        assertEquals("superSecretPassword123", secrets.get("password"));
        assertEquals("admin", secrets.get("username"));
        assertEquals("myapp.example.com", secrets.get("hostname"));
    }

    @Test
    void testWriteSecret() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        Map<String, String> secrets = new HashMap<>();
        secrets.put("api_key", "abc123xyz");
        secrets.put("api_secret", "secret456");

        assertDoesNotThrow(() -> client.writeSecret("secret/data/myapp/api", secrets));
    }

    @Test
    void testDisabledClient() throws VaultClient.VaultException {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .withEnabled(false)
                .build();

        assertFalse(client.isEnabled());

        String secret = client.getSecret("secret/data/myapp/config", "password");
        assertNull(secret);

        Map<String, String> secrets = client.getAllSecrets("secret/data/myapp/config");
        assertTrue(secrets.isEmpty());
    }

    @Test
    void testBuilderWithNamespace() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .withNamespace("tenant1")
                .build();

        assertNotNull(client);
    }

    @Test
    void testBuilderWithTimeouts() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .withConnectTimeout(60)
                .withReadTimeout(60)
                .withWriteTimeout(60)
                .build();

        assertNotNull(client);
    }

    @Test
    void testBuilderWithSkipTlsVerification() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("https://localhost:" + getMockServerPort())
                .withToken("test-token")
                .withSkipTlsVerification(true)
                .build();

        assertNotNull(client);
    }

    @Test
    void testBuilderMissingAddress() {
        assertThrows(NullPointerException.class, () -> {
            new VaultClient.Builder()
                    .withToken("test-token")
                    .build();
        });
    }

    @Test
    void testBuilderMissingToken() {
        assertThrows(NullPointerException.class, () -> {
            new VaultClient.Builder()
                    .withAddress("http://localhost:" + getMockServerPort())
                    .build();
        });
    }

    @Test
    void testBuilderDisabledNoValidation() {
        // Should not throw exception when disabled
        VaultClient client = new VaultClient.Builder()
                .withEnabled(false)
                .build();

        assertNotNull(client);
        assertFalse(client.isEnabled());
    }

    @Test
    void testGetSecretWithNullPath() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        assertThrows(NullPointerException.class, () -> {
            client.getSecret(null, "key");
        });
    }

    @Test
    void testGetSecretWithNullKey() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        assertThrows(NullPointerException.class, () -> {
            client.getSecret("secret/data/myapp/config", null);
        });
    }

    @Test
    void testGetAllSecretsWithNullPath() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        assertThrows(NullPointerException.class, () -> {
            client.getAllSecrets(null);
        });
    }

    @Test
    void testWriteSecretWithNullPath() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        assertThrows(NullPointerException.class, () -> {
            client.writeSecret(null, new HashMap<>());
        });
    }

    @Test
    void testWriteSecretWithNullSecrets() {
        VaultClient client = new VaultClient.Builder()
                .withAddress("http://localhost:" + getMockServerPort())
                .withToken("test-token")
                .build();

        assertThrows(NullPointerException.class, () -> {
            client.writeSecret("secret/data/myapp/config", null);
        });
    }
}
