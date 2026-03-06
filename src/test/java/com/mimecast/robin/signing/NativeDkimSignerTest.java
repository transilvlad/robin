package com.mimecast.robin.signing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NativeDkimSigner.
 * <p>
 * Generates a real RSA-2048 key pair in memory to sign a minimal RFC 5322 email
 * and verify the returned DKIM-Signature header value is structurally correct.
 */
class NativeDkimSignerTest {

    private static final String EMAIL_CONTENT =
            "From: sender@example.com\r\n" +
            "To: rcpt@example.com\r\n" +
            "Subject: Test\r\n" +
            "Date: Thu, 06 Mar 2026 12:00:00 +0000\r\n" +
            "Message-ID: <test@example.com>\r\n" +
            "\r\n" +
            "Test email body.\r\n";

    private NativeDkimSigner signer;
    private File emailFile;
    private String privateKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        signer = new NativeDkimSigner();

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

        emailFile = File.createTempFile("native-dkim-test-", ".eml");
        Files.writeString(emailFile.toPath(), EMAIL_CONTENT);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(emailFile.toPath());
    }

    @Test
    void testSignProducesValidDkimSignatureValue() throws IOException {
        Optional<String> result = signer.sign(emailFile, "example.com", "default", privateKeyBase64);

        assertTrue(result.isPresent(), "Signing should produce a result");
        String value = result.get();
        assertTrue(value.contains("v=1"), "Signature should contain v=1");
        assertTrue(value.contains("a=rsa-sha256"), "Signature should contain a=rsa-sha256");
        assertTrue(value.contains("d=example.com"), "Signature should contain d=example.com");
        assertTrue(value.contains("s=default"), "Signature should contain s=default");
        assertTrue(value.contains("b="), "Signature should contain b= (signature data)");
    }

    @Test
    void testSignDoesNotIncludeHeaderFieldName() throws IOException {
        Optional<String> result = signer.sign(emailFile, "example.com", "default", privateKeyBase64);

        assertTrue(result.isPresent());
        assertFalse(result.get().startsWith("DKIM-Signature:"),
                "Return value should not include the 'DKIM-Signature:' field name prefix");
    }

    @Test
    void testSignWithDifferentDomainAndSelector() throws IOException {
        Optional<String> result = signer.sign(emailFile, "esp.net", "2024q1", privateKeyBase64);

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("d=esp.net"));
        assertTrue(result.get().contains("s=2024q1"));
    }

    @Test
    void testSignThrowsOnInvalidKey() {
        assertThrows(IOException.class,
                () -> signer.sign(emailFile, "example.com", "default", "not-valid-base64!!!"));
    }
}
