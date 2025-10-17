package com.mimecast.robin.smtp.auth;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecureRandomTest {

    @Test
    void generate() throws DecoderException {
        Random random = new SecureRandom();
        String base64 = random.generate(42);
        byte[] bytes = Hex.decodeHex(base64);
        assertEquals(42, bytes.length);
    }
}
