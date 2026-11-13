package com.mimecast.robin.mime;

import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Content outside any MIME part (preamble and epilogue) stays out of getParts(), as RFC 2046
 * says, but is recovered through getUnframedParts() so scanners can still inspect it.
 */
class EmailParserUnframedTest {
    private static final String EICAR_BASE64 = "WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLV\r\n"
            + "NUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo=\r\n";
    private static final String PAYLOAD = "SIMULATED_VIRUS";
    private static final String PAYLOAD_BASE64 = "U0lNVUxBVEVEX1ZJUlVT\r\n";
    private static final String ATTACHMENT = "Content-Type: application/octet-stream; name=\"eicar.com\"\r\n"
            + "Content-Transfer-Encoding: base64\r\n"
            + "Content-Disposition: attachment; filename=\"eicar.com\"\r\n\r\n" + PAYLOAD_BASE64;

    @Test
    void attachmentAfterNestedClosingBoundaryIsUnframed() throws Exception {
        // The malformed fixture: the attachment follows the closing alternative boundary.
        try (EmailParser parser = parseFixture("src/test/resources/cases/sources/eicar-epilogue.eml")) {
            assertTrue(fileParts(parser.getParts()).isEmpty());
            List<FileMimePart> unframed = fileParts(parser.getUnframedParts());
            assertEquals(1, unframed.size());
            assertPayload(unframed.getFirst());
            assertEquals("eicar.com", unframed.getFirst().getHeaders().getFileName());
        }
    }

    @Test
    void wellFormedAttachmentIsAFramedPart() throws Exception {
        try (EmailParser parser = parseFixture("src/test/resources/cases/sources/eicar.eml")) {
            List<FileMimePart> framed = fileParts(parser.getParts());
            assertEquals(1, framed.size());
            assertPayload(framed.getFirst());
            assertTrue(parser.getUnframedParts().isEmpty());
        }
    }

    @Test
    void attachmentAfterOutermostClosingBoundaryIsUnframed() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n"
                + "--b--\r\n" + ATTACHMENT;
        try (EmailParser parser = parse(raw)) {
            assertTrue(fileParts(parser.getParts()).isEmpty());
            assertPayload(fileParts(parser.getUnframedParts()).getFirst());
        }
    }

    @Test
    void smuggledHeadersAfterBlankLinesAreStillRead() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n"
                + "--b--\r\n\r\n\r\n" + ATTACHMENT;
        try (EmailParser parser = parse(raw)) {
            assertPayload(fileParts(parser.getUnframedParts()).getFirst());
        }
    }

    @Test
    void attachmentInPreambleIsUnframed() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + ATTACHMENT
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n--b--\r\n";
        try (EmailParser parser = parse(raw)) {
            assertTrue(fileParts(parser.getParts()).isEmpty());
            assertPayload(fileParts(parser.getUnframedParts()).getFirst());
        }
    }

    @Test
    void unframedAttachmentInsideEnclosedMessageRemainsOwnedByParent() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\nContent-Type: message/rfc822\r\n\r\n"
                + "Content-Type: multipart/mixed; boundary=inner\r\n\r\n"
                + "--inner--\r\n" + ATTACHMENT
                + "--outer--\r\n";
        FileMimePart unframed;
        try (EmailParser parser = parse(raw)) {
            unframed = fileParts(parser.getUnframedParts()).getFirst();
            assertPayload(unframed);
            assertTrue(unframed.getFile().exists());
        }
        assertFalse(unframed.getFile().exists());
    }

    @Test
    void ordinaryPreambleAndBlankEpilogueYieldNoFileParts() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "This is a multi-part message in MIME format.\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n--b--\r\n\r\n";
        try (EmailParser parser = parse(raw)) {
            assertEquals(2, parser.getParts().size());
            assertTrue(fileParts(parser.getUnframedParts()).isEmpty());
        }
    }

    @Test
    void unparseableEpilogueIsKeptRawWithoutFailingTheMessage() throws Exception {
        String junk = "Bad: header\rsplit\r\n\r\nbody\r\n";
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n--b--\r\n" + junk;
        try (EmailParser parser = parse(raw)) {
            assertEquals(2, parser.getParts().size());
            FileMimePart opaque = fileParts(parser.getUnframedParts()).getFirst();
            assertEquals(junk, Files.readString(opaque.getFile().toPath(), StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void closeReleasesUnframedTemporaryFiles() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n--b--\r\n" + ATTACHMENT;
        EmailParser parser = parse(raw);
        FileMimePart part = fileParts(parser.getUnframedParts()).getFirst();
        assertTrue(part.getFile().exists());
        parser.close();
        assertFalse(part.getFile().exists());
    }

    private static EmailParser parse(String raw) throws Exception {
        return new EmailParser(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1))).parse();
    }

    private static EmailParser parseFixture(String path) throws Exception {
        String fixture = Files.readString(java.nio.file.Path.of(path), StandardCharsets.ISO_8859_1);
        assertTrue(fixture.contains(EICAR_BASE64));
        return parse(fixture.replace(EICAR_BASE64, PAYLOAD_BASE64));
    }

    private static List<FileMimePart> fileParts(List<MimePart> parts) {
        return parts.stream().filter(FileMimePart.class::isInstance).map(FileMimePart.class::cast).toList();
    }

    private static void assertPayload(FileMimePart part) throws Exception {
        assertEquals(PAYLOAD, Files.readString(part.getFile().toPath(), StandardCharsets.US_ASCII));
    }
}
