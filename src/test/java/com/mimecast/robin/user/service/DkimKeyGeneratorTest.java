package com.mimecast.robin.user.service;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimKeyGeneratorTest {

    @Test
    void generateRsa2048UsesExpectedKeySize() {
        DkimKeyGenerator generator = new DkimKeyGenerator();

        DkimKeyGenerator.GeneratedKey generated = generator.generateRsa2048();
        RSAPublicKey publicKey = assertInstanceOf(RSAPublicKey.class, generated.keyPair().getPublic());

        assertEquals("RSA_2048", generated.algorithm());
        assertEquals(2048, publicKey.getModulus().bitLength());
        assertTrue(generated.dnsPublicKey().length() > 255);
    }

    @Test
    void generateEd25519Produces44CharDnsPublicKey() {
        DkimKeyGenerator generator = new DkimKeyGenerator();

        DkimKeyGenerator.GeneratedKey generated = generator.generateEd25519();
        byte[] raw = Base64.getDecoder().decode(generated.dnsPublicKey());

        assertEquals("ED25519", generated.algorithm());
        assertEquals(44, generated.dnsPublicKey().length());
        assertEquals(32, raw.length);
    }
}
