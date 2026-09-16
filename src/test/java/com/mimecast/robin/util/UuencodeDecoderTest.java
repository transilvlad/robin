package com.mimecast.robin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UuencodeDecoder class.
 */
class UuencodeDecoderTest {

    @Test
    @DisplayName("Decodes a classic single-line uuencoded payload")
    void decodesSingleLinePayload() {
        String raw = "begin 644 cat.txt\r\n" +
                "#0V%T\r\n" +
                "`\r\n" +
                "end\r\n";

        Optional<UuencodeDecoder.Result> result = UuencodeDecoder.decode(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(result.isPresent());
        assertEquals("cat.txt", result.get().filename());
        assertArrayEquals("Cat".getBytes(StandardCharsets.ISO_8859_1), result.get().content());
    }

    @Test
    @DisplayName("Decodes a multi-line uuencoded payload")
    void decodesMultiLinePayload() {
        // "Hello, uuencode world!" (22 bytes), independently encoded per the uuencode algorithm.
        String raw = "begin 644 hello.txt\r\n" +
                "62&5L;&\\L('5U96YC;V1E('=O<FQD(0``\r\n" +
                "`\r\n" +
                "end\r\n";

        Optional<UuencodeDecoder.Result> result = UuencodeDecoder.decode(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(result.isPresent());
        assertEquals("hello.txt", result.get().filename());
        assertEquals("Hello, uuencode world!", new String(result.get().content(), StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("looksLikeUuencode detects a begin preamble")
    void looksLikeUuencodeDetectsPreamble() {
        assertTrue(UuencodeDecoder.looksLikeUuencode("begin 644 file.bin\r\ndata\r\nend\r\n".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    @DisplayName("looksLikeUuencode rejects plain content")
    void looksLikeUuencodeRejectsPlainContent() {
        assertFalse(UuencodeDecoder.looksLikeUuencode("Just a normal email body.".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    @DisplayName("decode returns empty for content without a begin preamble")
    void decodeReturnsEmptyWithoutPreamble() {
        assertTrue(UuencodeDecoder.decode("Just a normal email body.".getBytes(StandardCharsets.ISO_8859_1)).isEmpty());
    }

    @Test
    @DisplayName("decode returns empty for null input")
    void decodeReturnsEmptyForNull() {
        assertTrue(UuencodeDecoder.decode(null).isEmpty());
        assertFalse(UuencodeDecoder.looksLikeUuencode(null));
    }
}
