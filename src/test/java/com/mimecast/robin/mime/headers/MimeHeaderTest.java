package com.mimecast.robin.mime.headers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MimeHeader class.
 */
class MimeHeaderTest {

    @Test
    @DisplayName("getValue returns the raw, undecoded wire value")
    void getValueReturnsRawValue() {
        MimeHeader header = new MimeHeader("Subject: =?UTF-8?B?UsO2YmluIGxpa2Vz?=");
        assertEquals("=?UTF-8?B?UsO2YmluIGxpa2Vz?=", header.getValue());
    }

    @Test
    @DisplayName("getDecodedValue decodes a Base64 UTF-8 encoded word")
    void getDecodedValueDecodesBase64Utf8() {
        MimeHeader header = new MimeHeader("Subject: =?UTF-8?B?UsO2YmluIGxpa2Vz?=");
        assertEquals("Röbin likes", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue decodes a Quoted-Printable UTF-8 encoded word")
    void getDecodedValueDecodesQuotedPrintableUtf8() {
        MimeHeader header = new MimeHeader("Subject: =?UTF-8?Q?R=C3=B6bin_likes?=");
        assertEquals("Röbin likes", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue decodes an ISO-8859-1 encoded word")
    void getDecodedValueDecodesLatin1() {
        MimeHeader header = new MimeHeader("Subject: =?ISO-8859-1?Q?R=F6bin?=");
        assertEquals("Röbin", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue decodes multiple encoded words mixed with plain text")
    void getDecodedValueDecodesMultipleWords() {
        MimeHeader header = new MimeHeader("Subject: =?UTF-8?B?UsO2Ymlu?= and =?UTF-8?B?TGFkeSBSw7ZiaW4=?=");
        assertEquals("Röbin and Lady Röbin", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue leaves plain ASCII values unchanged")
    void getDecodedValuePassesThroughPlainText() {
        MimeHeader header = new MimeHeader("Subject: Robin likes plain text");
        assertEquals("Robin likes plain text", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue falls back to the raw value for an unsupported charset")
    void getDecodedValueFallsBackOnUnsupportedCharset() {
        MimeHeader header = new MimeHeader("Subject: =?not-a-real-charset?Q?Robin?=");
        assertEquals("=?not-a-real-charset?Q?Robin?=", header.getDecodedValue());
    }

    @Test
    @DisplayName("getDecodedValue is cached after first call")
    void getDecodedValueIsCached() {
        MimeHeader header = new MimeHeader("Subject: =?UTF-8?B?UsO2YmluIGxpa2Vz?=");
        String first = header.getDecodedValue();
        String second = header.getDecodedValue();
        assertSame(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Subject", "Content-Type", "X-Robin-Filename", "X-Custom-123", "a", "123"})
    @DisplayName("isValidName accepts letters, digits, and hyphens")
    void isValidNameAcceptsValidNames(String name) {
        assertTrue(MimeHeader.isValidName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad Header", "Sub:ject", "X_Custom", "héader", "Header\t", "Header\r"})
    @DisplayName("isValidName rejects names with spaces, colons, or other invalid characters")
    void isValidNameRejectsInvalidNames(String name) {
        assertFalse(MimeHeader.isValidName(name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("isValidName rejects null and empty names")
    void isValidNameRejectsNullAndEmpty(String name) {
        assertFalse(MimeHeader.isValidName(name));
    }

    @Test
    @DisplayName("isValid reflects the constructed header's own name")
    void isValidReflectsOwnName() {
        assertTrue(new MimeHeader("Subject: hello").isValid());
        assertFalse(new MimeHeader("Bad Header: hello").isValid());
    }
}
