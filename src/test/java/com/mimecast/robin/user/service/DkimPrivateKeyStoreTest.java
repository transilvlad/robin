package com.mimecast.robin.user.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DkimPrivateKeyStoreTest {

    @Test
    void encryptDecryptRoundTripRestoresOriginalContent() {
        DkimPrivateKeyStore store = new DkimPrivateKeyStore();
        byte[] key = store.generateKey();
        String plaintext = """
                -----BEGIN PRIVATE KEY-----
                c29tZS1kZW1vLWRraW0ta2V5
                -----END PRIVATE KEY-----
                """;

        String encrypted = store.encrypt(plaintext, key);
        String decrypted = store.decrypt(encrypted, key);

        assertNotEquals(plaintext, encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void decryptRejectsInvalidPayload() {
        DkimPrivateKeyStore store = new DkimPrivateKeyStore();
        byte[] key = store.generateKey();

        assertThrows(IllegalArgumentException.class, () -> store.decrypt("not-a-valid-payload", key));
    }
}
