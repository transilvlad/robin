package com.mimecast.robin.scanners;

import com.mimecast.robin.smtp.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RspamdMessageMutationsTest {
    @TempDir
    Path directory;

    @Test
    void trustedSignatureAndHeadersPreserveBinaryBodyAndExistingHeaderBytes() throws Exception {
        byte[] body = new byte[]{0, 1, (byte) 255, '\r', '\n', 'x', '\n'};
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write("From: a@example.com\r\nX-Old: first\r\n\tfolded\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        message.write(body);
        MessageEnvelope envelope = envelope(message.toByteArray());
        var result = Map.<String, Object>of("milter", Map.of(
                "remove_headers", Map.of("X-Old", 0),
                "add_headers", Map.of("DKIM-Signature", Map.of("value", "v=1; d=example.com;\r\n b=synthetic", "order", 0),
                        "Authentication-Results", Map.of("value", "fixture.test; dkim=pass", "order", 1))));
        try {
            assertTrue(RspamdMessageMutations.apply(envelope, result));
            byte[] updated = envelope.readMessageBytes();
            String header = new String(updated, StandardCharsets.ISO_8859_1);
            assertTrue(header.startsWith("DKIM-Signature: v=1;"));
            assertTrue(header.contains("From: a@example.com\r\n"));
            assertFalse(header.contains("X-Old"));
            assertArrayEquals(body, Arrays.copyOfRange(updated, updated.length - body.length, updated.length));
        } finally {
            envelope.getMessageSource().release();
        }
    }

    @Test
    void scannerCannotInjectAnotherHeaderThroughUnfoldedNewlineOrChangeAuthor() throws Exception {
        MessageEnvelope envelope = envelope("From: a@example.com\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> RspamdMessageMutations.apply(envelope,
                Map.of("milter", Map.of("add_headers", Map.of("X-Test", "ok\r\nInjected: no")))));
        assertThrows(IOException.class, () -> RspamdMessageMutations.apply(envelope,
                Map.of("milter", Map.of("add_headers", Map.of("From", "foreign@example.com")))));
        assertThrows(IOException.class, () -> RspamdMessageMutations.apply(envelope,
                Map.of("milter", Map.of("add_rcpt", java.util.List.of("foreign@example.com")))));
    }

    @Test
    void existingSenderSuppliedSignatureDoesNotCountAsFreshSigningProof() throws Exception {
        MessageEnvelope envelope = envelope("DKIM-Signature: forged\r\nFrom: a@example.com\r\n\r\nbody"
                .getBytes(StandardCharsets.US_ASCII));
        assertFalse(RspamdMessageMutations.apply(envelope, Map.of()));
    }

    private MessageEnvelope envelope(byte[] message) {
        return new MessageEnvelope().setBytes(message).setFile(directory.resolve("input.eml").toString());
    }
}
