package com.mimecast.robin.util;

import org.apache.commons.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class QuotedPrintableDecoderTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Hel=\\r\\nlo=21\\r\\n|Hello!\\r\\n",
            "Hel=\\nlo=21\\n|Hello!\\n",
            "A=\\r\\n\\r\\nB\\r\\n|A\\r\\nB\\r\\n",
            "A=\\n\\nB\\n|A\\nB\\n",
            "A=\\r\\n\\r\\n\\r\\nB|A\\r\\n\\r\\nB",
            "A\\r\\n\\r\\nB|A\\r\\n\\r\\nB",
            "A\\n\\nB|A\\n\\nB"
    })
    void preservesHardBreaks(String encoded, String decoded) throws Exception {
        assertArrayEquals(bytes(decoded), QuotedPrintableDecoder.decode(bytes(encoded)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "=G0", "=0", "=\\rX", "=\\r"})
    void rejectsIncompleteOrInvalidEscapes(String encoded) {
        assertThrows(DecoderException.class, () -> QuotedPrintableDecoder.decode(bytes(encoded)));
    }

    @Test
    void decodesBinaryAndPreservesEmptyInput() throws Exception {
        assertArrayEquals(new byte[]{0, 61, (byte) 255},
                QuotedPrintableDecoder.decode(bytes("=00=3D=FF")));
        assertArrayEquals(new byte[0], QuotedPrintableDecoder.decode(new byte[0]));
        assertNull(QuotedPrintableDecoder.decode(null));
    }

    private static byte[] bytes(String text) {
        return text.replace("\\r", "\r").replace("\\n", "\n").getBytes(StandardCharsets.US_ASCII);
    }
}
