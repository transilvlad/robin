package com.mimecast.robin.mime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailParserFramingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesStandaloneCarriageReturnBeforeAnyIdentityCanBeUsed(boolean headersOnly) {
        String raw = "From: first@origin.test\r\r\nFrom: second@other.test\r\n\r\nBody";
        try (EmailParser parser = parser(raw)) {
            assertThrows(IOException.class, () -> parser.parse(headersOnly));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\u0000", "\u000b", "\u000c", "\u001b", "\u007f"})
    void refusesProhibitedRawHeaderControls(String control) {
        try (EmailParser parser = parser("From: first@origin.test" + control
                + " hidden\r\n\r\nBody")) {
            assertThrows(IOException.class, () -> parser.parse(true));
        }
    }

    @Test
    void refusesTrailingHeaderCarriageReturnAtEndOfInput() {
        try (EmailParser parser = parser("From: first@origin.test\r")) {
            assertThrows(IOException.class, () -> parser.parse(true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void acceptsLegalHeadersFoldingAndUtf8WithoutInspectingBodyControls(String eol) throws Exception {
        String body = "Body\rcontent\u0000\u000b\u000c\u001b\u007f\r";
        String raw = "From: First <first@origin.test>" + eol
                + "X-Note: caf\u00e9\u0085" + eol + "\tfolded" + eol
                + "Content-Type: application/octet-stream" + eol + eol + body;
        try (EmailParser parser = parser(raw).parse()) {
            assertTrue(parser.getHeaders().get("X-Note").orElseThrow().getValue().contains("folded"));
            assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), parser.getParts().getFirst().getBytes());
        }
    }

    @Test
    void preservesOrderedDuplicateIdentityEvidence() throws Exception {
        try (EmailParser parser = parser("From: first@origin.test\r\nfRoM: second@other.test\r\n"
                + "Sender: first@origin.test\r\nSENDER: second@other.test\r\n\r\nBody").parse(true)) {
            assertEquals(List.of("first@origin.test", "second@other.test"),
                    parser.getHeaders().getAll("From").stream().map(h -> h.getValue()).toList());
            assertEquals(List.of("first@origin.test", "second@other.test"),
                    parser.getHeaders().getAll("Sender").stream().map(h -> h.getValue()).toList());
        }
    }

    @Test
    void inputCloseFailureDoesNotLeaveSuccessfulParserStatus() {
        ByteArrayInputStream input = new ByteArrayInputStream(
                "From: first@origin.test\r\n\r\nBody".getBytes(StandardCharsets.US_ASCII)) {
            @Override
            public void close() throws IOException {
                throw new IOException("Synthetic close failure");
            }
        };
        try (EmailParser parser = new EmailParser(input)) {
            assertThrows(IOException.class, () -> parser.parse(true));
            assertFalse(parser.hasValidHeaders());
        }
    }

    private static EmailParser parser(String message) {
        return new EmailParser(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)), 1);
    }
}
