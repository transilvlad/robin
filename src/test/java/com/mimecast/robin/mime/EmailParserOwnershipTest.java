package com.mimecast.robin.mime;

import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailParserOwnershipTest {
    @Test
    void transferHasOneOwnerAndIsOnlyAllowedAfterFullParse() throws Exception {
        byte[] raw = "Content-Type: application/octet-stream\r\n\r\nHello".getBytes(StandardCharsets.US_ASCII);
        EmailParser parser = new EmailParser(new ByteArrayInputStream(raw));
        assertThrows(IllegalStateException.class, parser::detachParts);
        parser.parse();
        FileMimePart file = (FileMimePart) parser.getParts().getFirst();
        Path path = file.getFile().toPath();
        List<MimePart> transferred = parser.detachParts();
        try {
            assertEquals(List.of(file), transferred);
            assertTrue(parser.getParts().isEmpty());
            assertTrue(parser.detachParts().isEmpty());
            parser.close();
            assertThrows(IllegalStateException.class, parser::detachParts);
            assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII), file.getBytes());
        } finally {
            file.close();
        }
        assertFalse(Files.exists(path));
        try (EmailParser headers = new EmailParser(new ByteArrayInputStream(raw)).parse(true)) {
            assertThrows(IllegalStateException.class, headers::detachParts);
        }
    }

    @Test
    void parseFailureClosesInputAndAlreadyCreatedFiles() throws Exception {
        byte[] raw = ("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\n\r\nHello\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        boolean[] closed = {false};
        InputStream failing = new ByteArrayInputStream(raw) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }

            @Override
            public synchronized int read() {
                return super.read();
            }
        };
        InputStream source = new InputStream() {
            @Override
            public int read() throws IOException {
                int value = failing.read();
                if (value == -1) throw new IOException("Synthetic read failure");
                return value;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        EmailParser parser = new EmailParser(source, 1);
        assertThrows(IOException.class, parser::parse);
        assertTrue(closed[0]);
        assertEquals(2, parser.getParts().size());
        FileMimePart file = assertInstanceOf(FileMimePart.class, parser.getParts().get(1));
        assertFalse(Files.exists(file.getFile().toPath()));
        parser.close();
        assertThrows(IllegalStateException.class, parser::detachParts);
    }

    @Test
    void transferringVisiblePartsDoesNotReleaseOwnershipOfRemovedFiles() throws Exception {
        byte[] raw = "Content-Type: application/octet-stream\r\n\r\nHello".getBytes(StandardCharsets.US_ASCII);
        FileMimePart file;
        try (EmailParser parser = new EmailParser(new ByteArrayInputStream(raw)).parse()) {
            file = (FileMimePart) parser.getParts().removeFirst();
            assertTrue(parser.detachParts().isEmpty());
            assertTrue(Files.exists(file.getFile().toPath()));
        }
        try {
            assertFalse(Files.exists(file.getFile().toPath()));
        } finally {
            file.close();
        }
    }

    @Test
    void sharedParserReadsDoNotMutateRawMessageOrEnvelopeAddresses() throws Exception {
        byte[] raw = ("From: sender@example.test\r\nTo: header@example.test\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nHello").getBytes(StandardCharsets.US_ASCII);
        MessageEnvelope envelope = new MessageEnvelope().setMail("bounce@example.test")
                .addRcpt("recipient@example.test").setBytes(raw.clone());
        try (EmailParser parser = new EmailParser(envelope.openMessageStream()).parse()) {
            for (int consumer = 0; consumer < 3; consumer++) {
                assertSame(parser, parser.parse());
                assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII), parser.getParts().getFirst().getBytes());
            }
        }
        assertArrayEquals(raw, envelope.readMessageBytes());
        assertEquals("bounce@example.test", envelope.getMail());
        assertEquals(List.of("recipient@example.test"), envelope.getRcpts());
    }

    @Test
    void repeatedParserCloseDoesNotDeleteRecreatedPartPath() throws Exception {
        byte[] raw = "Content-Type: application/octet-stream\r\n\r\nHello".getBytes(StandardCharsets.US_ASCII);
        EmailParser parser = new EmailParser(new ByteArrayInputStream(raw)).parse();
        FileMimePart part = (FileMimePart) parser.getParts().getFirst();
        Path path = part.getFile().toPath();
        try {
            parser.close();
            byte[] replacement = {4, 5, 6};
            Files.write(path, replacement);
            parser.close();
            assertTrue(Files.exists(path));
            assertArrayEquals(replacement, Files.readAllBytes(path));
            assertThrows(IOException.class, part::getBytes);
        } finally {
            parser.close();
            Files.deleteIfExists(path);
        }
    }
}
