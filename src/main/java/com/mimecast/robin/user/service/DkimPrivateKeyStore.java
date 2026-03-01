package com.mimecast.robin.user.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Encrypts and decrypts DKIM private keys with AES-256-GCM.
 */
public class DkimPrivateKeyStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String VERSION = "v1";

    /**
     * Generates a random AES-256 key.
     *
     * @return 32-byte key
     */
    public byte[] generateKey() {
        byte[] key = new byte[AES_256_KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    /**
     * Encrypts plaintext private key content.
     *
     * @param plaintext value to encrypt
     * @param key AES-256 key bytes
     * @return versioned encrypted payload
     */
    public String encrypt(String plaintext, byte[] key) {
        Objects.requireNonNull(plaintext, "plaintext");
        validateKey(key);
        byte[] iv = new byte[GCM_IV_BYTES];
        RANDOM.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + base64(iv) + ":" + base64(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt DKIM private key", e);
        }
    }

    /**
     * Decrypts an encrypted payload from {@link #encrypt(String, byte[])}.
     *
     * @param payload encrypted payload
     * @param key AES-256 key bytes
     * @return decrypted plaintext
     */
    public String decrypt(String payload, byte[] key) {
        Objects.requireNonNull(payload, "payload");
        validateKey(key);
        String[] parts = payload.split(":");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Invalid encrypted payload format");
        }

        byte[] iv = fromBase64(parts[1]);
        byte[] ciphertext = fromBase64(parts[2]);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt DKIM private key", e);
        }
    }

    private static void validateKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] fromBase64(String value) {
        return Base64.getDecoder().decode(value);
    }
}
