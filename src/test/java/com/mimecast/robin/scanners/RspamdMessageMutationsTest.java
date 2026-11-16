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

    @Test
    void scannerChangesAndOwnHeadersAreAppliedTogether() throws Exception {
        byte[] body = new byte[]{'b', 0, (byte) 0xff, '\r', '\n'};
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(("Received: from upstream\r\nAuthentication-Results: mx.robin.test; spf=pass\r\n"
                + "Authentication-Results: upstream.example; dkim=pass\r\nX-Spam-Status: No, score=-100\r\n"
                + "From: a@example.com\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        message.write(body);
        MessageEnvelope envelope = envelope(message.toByteArray());
        var result = Map.<String, Object>of("dkim-signature", "v=1; d=example.com; b=synthetic");
        try {
            assertTrue(RspamdMessageMutations.apply(envelope, result,
                    java.util.List.of(new String[]{"Authentication-Results", "mx.robin.test;\r\n\tspf=pass"},
                            new String[]{"X-Spam-Status", "No, score=0.50"}),
                    (name, value) -> name.equals("authentication-results") && value.startsWith("mx.robin.test;")
                            || name.equals("x-spam-status")));

            byte[] updated = envelope.readMessageBytes();
            String header = new String(updated, StandardCharsets.ISO_8859_1);
            assertTrue(header.startsWith("Authentication-Results: mx.robin.test;\r\n\tspf=pass\r\n"
                    + "X-Spam-Status: No, score=0.50\r\nDKIM-Signature: v=1;"), header);
            assertTrue(header.contains("Authentication-Results: upstream.example; dkim=pass\r\n"), header);
            assertFalse(header.contains("score=-100"), header);
            assertArrayEquals(body, Arrays.copyOfRange(updated, updated.length - body.length, updated.length));
        } finally {
            envelope.getMessageSource().release();
        }
    }

    private MessageEnvelope envelope(byte[] message) {
        return new MessageEnvelope().setBytes(message).setFile(directory.resolve("input.eml").toString());
    }
}
