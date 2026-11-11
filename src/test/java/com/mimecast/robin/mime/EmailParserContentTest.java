package com.mimecast.robin.mime;

import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.MultipartMimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class EmailParserContentTest {
    private static EmailParser parse(String message) throws Exception {
        return new EmailParser(new ByteArrayInputStream(message.getBytes(StandardCharsets.US_ASCII))).parse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    void separatorEndsHeadersEvenAfterSemicolon(String eol) throws Exception {
        String body = " Indented body" + eol + "Next line" + eol;
        try (EmailParser parser = parse("Content-Type: text/plain" + eol + "X-Trace: token;" + eol + eol + body)) {
            assertEquals("token;", parser.getHeaders().get("X-Trace").orElseThrow().getValue());
            assertArrayEquals(body.getBytes(StandardCharsets.US_ASCII), parser.getParts().getFirst().getBytes());
        }
    }

    @Test
    void keepsHeaderLookingBodyAndRealFoldingSeparate() throws Exception {
        String body = "\tSubject: body only\r\nFrom: body@example.test\r\n";
        try (EmailParser parser = parse("X-Trace: token;\r\n\r\n" + body)) {
            assertTrue(parser.getHeaders().get("Subject").isEmpty());
            assertTrue(parser.getHeaders().get("From").isEmpty());
            assertArrayEquals(body.getBytes(StandardCharsets.US_ASCII), parser.getParts().getFirst().getBytes());
        }
    }

    @Test
    void skipsHeaderShapedPreambleAndEpilogue() throws Exception {
        String raw = "Content-Type: multipart/mixed;\r\n\tboundary=b\r\n\r\n"
                + "Content-Type: text/plain\r\n\r\nPreamble ignored.\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n"
                + "--b--\r\nSubject: epilogue\r\n\r\nIgnored\r\n";
        try (EmailParser parser = parse(raw)) {
            assertEquals(2, parser.getParts().size());
            assertInstanceOf(MultipartMimePart.class, parser.getParts().getFirst());
            assertArrayEquals("Hello\r\n".getBytes(StandardCharsets.US_ASCII), parser.getParts().get(1).getBytes());
            assertTrue(parser.getHeaders().get("Subject").isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" --b", "\t--b", "--b-extra", "--B", "inside --b text", "--b--x"})
    void retainsBoundaryLookalikes(String line) throws Exception {
        String body = "Before\r\n" + line + "\r\nAfter\r\n";
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b \t\r\nContent-Type: text/plain\r\n\r\n" + body + "--b--\t \r\n")) {
            assertEquals(2, parser.getParts().size());
            assertArrayEquals(body.getBytes(StandardCharsets.US_ASCII), parser.getParts().get(1).getBytes());
        }
    }

    @Test
    void defaultsUntypedRootAndChildrenWithoutChangingWireHeaders() throws Exception {
        try (EmailParser parser = parse("From: sender@example.test\r\n\r\nHello\r\n")) {
            assertTrue(parser.getHeaders().get("Content-Type").isEmpty());
            assertEquals(1, parser.getParts().size());
            assertInstanceOf(TextMimePart.class, parser.getParts().getFirst());
            assertEquals("text/plain", parser.getParts().getFirst().getHeader("Content-Type").getCleanValue());
            assertEquals("Hello\r\n", ((TextMimePart) parser.getParts().getFirst()).getContent());
        }
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\n\r\nHello\r\n--b\r\n\r\n--b--\r\n")) {
            assertEquals(3, parser.getParts().size());
            assertEquals(7, parser.getParts().get(1).getSize());
            assertEquals(0, parser.getParts().get(2).getSize());
        }
    }

    @Test
    void dispatchesCaseInsensitiveTypesWithoutChangingParameters() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            try (EmailParser parser = parse("Content-Type: MULTIPART/MIXED; boundary=AaB\r\n\r\n"
                    + "--AaB\r\nContent-Type: TEXT/PLAIN; name=\"Report.XML\"\r\n\r\nHello\r\n--AaB--\r\n")) {
                assertEquals(2, parser.getParts().size());
                assertInstanceOf(MultipartMimePart.class, parser.getParts().getFirst());
                assertInstanceOf(TextMimePart.class, parser.getParts().get(1));
                assertEquals("AaB", parser.getParts().getFirst().getHeader("Content-Type").getParameter("boundary"));
                assertEquals("Report.XML", parser.getParts().get(1).getHeader("Content-Type").getParameter("name"));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void nestedMultipartDiscardsItsEpilogueWithoutLosingSibling() throws Exception {
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\nContent-Type: multipart/alternative; boundary=inner\r\n\r\n"
                + "Content-Type: text/html\r\n\r\nPreamble\r\n"
                + "--inner\r\nContent-Type: text/plain\r\n\r\nFirst\r\n--inner--\r\n"
                + "Content-Type: text/html\r\n\r\nEpilogue\r\n"
                + "--outer\r\nContent-Type: text/plain\r\n\r\nSecond\r\n--outer--\r\n")) {
            assertEquals(4, parser.getParts().size());
            assertEquals("First\r\n", ((TextMimePart) parser.getParts().get(2)).getContent());
            assertEquals("Second\r\n", ((TextMimePart) parser.getParts().get(3)).getContent());
        }
    }

    @Test
    void retainsEnclosedMessageAndExistingDescendants() throws Exception {
        String enclosed = "From: sender@example.test\r\nSubject: original\r\nX-Note: first\r\n"
                + "\tsecond\r\nContent-Type: text/plain\r\n\r\nBody\r\n";
        Path file;
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: MESSAGE/RFC822\r\nContent-Disposition: attachment; filename=message.eml\r\n"
                + "Content-ID: <message@example.test>\r\n\r\n" + enclosed + "--b--\r\n")) {
            assertEquals(3, parser.getParts().size());
            FileMimePart container = assertInstanceOf(FileMimePart.class, parser.getParts().get(1));
            assertArrayEquals(enclosed.getBytes(StandardCharsets.US_ASCII), container.getBytes());
            assertEquals("<message@example.test>", container.getHeader("Content-ID").getValue());
            assertEquals("message.eml", container.getHeader("Content-Disposition").getParameter("filename"));
            assertEquals("Body\r\n", ((TextMimePart) parser.getParts().get(2)).getContent());
            file = container.getFile().toPath();
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void digestDefaultsDoNotCreateEmptyTrailingAttachments() throws Exception {
        String enclosed = "From: sender@example.test\r\nSubject: enclosed\r\n\r\nHello\r\n";
        try (EmailParser parser = parse("Content-Type: multipart/digest; boundary=b\r\n\r\n"
                + "--b\r\n\r\n" + enclosed + "--b\r\n\r\n--b--\r\n")) {
            assertEquals(3, parser.getParts().size());
            assertEquals("message/rfc822", parser.getParts().get(1).getHeader("Content-Type").getCleanValue());
            assertArrayEquals(enclosed.getBytes(StandardCharsets.US_ASCII), parser.getParts().get(1).getBytes());
            assertEquals("Hello\r\n", ((TextMimePart) parser.getParts().get(2)).getContent());
        }
        for (String ending : List.of("--b--\r\n", "--b\r\n--b--\r\n", "--b\r\n\r\n--b--\r\n")) {
            try (EmailParser parser = parse("Content-Type: multipart/digest; boundary=b\r\n\r\n" + ending)) {
                assertEquals(1, parser.getParts().size());
            }
        }
    }

    @Test
    void retainsExplicitEmptyAttachmentAndMissingBoundaryAsOpaque() throws Exception {
        try (EmailParser parser = parse("Content-Type: multipart/digest; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment\r\n"
                + "Content-ID: <empty@example.test>\r\n\r\n--b--\r\n")) {
            assertEquals(2, parser.getParts().size());
            assertEquals(0, parser.getParts().get(1).getSize());
            assertEquals("<empty@example.test>", parser.getParts().get(1).getHeader("Content-ID").getValue());
        }
        try (EmailParser parser = parse("Content-Type: multipart/mixed;\r\n\r\n\tboundary=not-a-header\r\n")) {
            MimePart part = parser.getParts().getFirst();
            assertInstanceOf(FileMimePart.class, part);
            assertArrayEquals("\tboundary=not-a-header\r\n".getBytes(StandardCharsets.US_ASCII), part.getBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/octet-stream"})
    void decodesQuotedPrintableForTextAndFileParts(String type) throws Exception {
        for (String eol : List.of("\n", "\r\n")) {
            try (EmailParser parser = parse("Content-Type: " + type + eol
                    + "Content-Transfer-Encoding: quoted-printable" + eol + eol
                    + "A=" + eol + eol + "B=21" + eol)) {
                assertArrayEquals(("A" + eol + "B!" + eol).getBytes(StandardCharsets.US_ASCII),
                        parser.getParts().getFirst().getBytes());
            }
        }

        try (EmailParser parser = parse("Content-Type: " + type
                + "\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nA=G0\r\n")) {
            assertArrayEquals("A=G0\r\n".getBytes(StandardCharsets.US_ASCII), parser.getParts().getFirst().getBytes());
        }
    }

    @Test
    void transferEncodedEnclosedMessageRetainsExactBytesAndNestedFiles() throws Exception {
        String enclosed = "From: sender@example.test\r\nSubject: original\r\n"
                + "Content-Type: multipart/mixed; boundary=inner\r\n\r\n"
                + "--inner\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nSGVsbG8=\r\n--inner--\r\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(enclosed.getBytes(StandardCharsets.US_ASCII));
        List<Path> files;
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\nContent-Type: message/rfc822\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + encoded + "\r\n--outer--\r\n")) {
            assertEquals(4, parser.getParts().size());
            assertArrayEquals(enclosed.getBytes(StandardCharsets.US_ASCII), parser.getParts().get(1).getBytes());
            assertInstanceOf(MultipartMimePart.class, parser.getParts().get(2));
            MimePart binary = parser.getParts().get(3);
            assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII), binary.getBytes());
            assertArrayEquals(binary.getBytes(), binary.getBytes());
            files = parser.getParts().stream().filter(FileMimePart.class::isInstance)
                    .map(part -> ((FileMimePart) part).getFile().toPath()).toList();
            assertEquals(2, files.size());
        }
        for (Path file : files) assertFalse(Files.exists(file));
    }

    @Test
    void truncatedNestedMultipartPreservesFollowingOuterSibling() throws Exception {
        try (EmailParser parser = parse("Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\nContent-Type: multipart/mixed; boundary=inner\r\n\r\n"
                + "--inner\r\nContent-Type: text/plain\r\n\r\nFirst\r\n"
                + "--outer\r\nContent-Type: text/plain\r\n\r\nSecond\r\n--outer--\r\n")) {
            assertEquals(4, parser.getParts().size());
            assertEquals("First\r\n", ((TextMimePart) parser.getParts().get(2)).getContent());
            assertEquals("Second\r\n", ((TextMimePart) parser.getParts().get(3)).getContent());
        }
    }
}
