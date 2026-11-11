package com.mimecast.robin.mime.parts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class TextMimePartTest {
    @ParameterizedTest
    @CsvSource({
            "Shift_JIS,82a0,\u3042",
            "windows-1252,e9,\u00e9",
            "UTF-8,c3a9,\u00e9",
            "ISO-8859-1,c3a9,\u00c3\u00a9",
            "utf8,c3a9,\u00e9"
    })
    void usesUsableDeclaredCharset(String charset, String hex, String expected) throws Exception {
        byte[] bytes = HexFormat.of().parseHex(hex);
        TextMimePart part = new TextMimePart(bytes);
        part.addHeader("Content-Type", "text/plain; charset=\"" + charset + "\"");
        assertEquals(expected, part.getContent());
        assertEquals(expected, part.getContent());
        assertArrayEquals(bytes, part.getBytes());
    }

    @Test
    void recoversUnknownAbsentAndUnusableDeclarations() throws Exception {
        for (String type : new String[]{"text/plain", "text/plain; charset=x-unknown",
                "text/plain; charset=\"invalid charset\""}) {
            TextMimePart part = new TextMimePart("Hello".getBytes(StandardCharsets.US_ASCII));
            part.addHeader("Content-Type", type);
            assertEquals("Hello", part.getContent());
        }
        byte[] invalidUtf8 = {(byte) 0xe9};
        TextMimePart invalid = new TextMimePart(invalidUtf8);
        invalid.addHeader("Content-Type", "text/plain; charset=UTF-8");
        assertNotNull(invalid.getContent());
        assertArrayEquals(invalidUtf8, invalid.getBytes());
        assertEquals("", new TextMimePart(new byte[0]).getContent());
    }
}
