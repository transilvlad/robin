package com.mimecast.robin.smtp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StreamMessageSourceTest {
    private static final String EML = "Subject: test\r\n\r\nbody\r\n";

    private static StreamMessageSource source(String content) {
        return new StreamMessageSource(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void openStreamIsRepeatable() throws IOException {
        StreamMessageSource source = source(EML);

        try (InputStream first = source.openStream()) {
            assertEquals(EML, new String(first.readAllBytes(), StandardCharsets.UTF_8));
        }

        // The one-shot stream is gone by now; a second consumer must still get the payload.
        try (InputStream second = source.openStream()) {
            assertEquals(EML, new String(second.readAllBytes(), StandardCharsets.UTF_8));
        }

        source.release();
    }

    @Test
    void readAllBytesAfterOpenStreamReturnsFullPayload() throws IOException {
        StreamMessageSource source = source(EML);

        try (InputStream stream = source.openStream()) {
            assertEquals(EML.length(), stream.readAllBytes().length);
        }

        assertEquals(EML, new String(source.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(EML.length(), source.size());

        source.release();
    }

    @Test
    void spoolsLazilyAndOnlyOnce() throws IOException {
        StreamMessageSource source = source(EML);
        assertEquals(Optional.empty(), source.getMaterializedPath(), "Must not spool before first read");

        source.openStream().close();
        Optional<Path> spooled = source.getMaterializedPath();
        assertTrue(spooled.isPresent(), "Must expose the spool path once materialized");

        source.openStream().close();
        assertEquals(spooled, source.getMaterializedPath(), "Must reuse the same spool file");

        source.release();
    }

    @Test
    void releaseDeletesSpoolFile() throws IOException {
        StreamMessageSource source = source(EML);
        source.openStream().close();

        Path path = source.getMaterializedPath().orElseThrow();
        assertTrue(Files.exists(path));

        source.release();
        assertFalse(Files.exists(path), "Spool file must be deleted on release");
    }

    @Test
    void releaseBeforeAnyReadIsNoOp() {
        assertDoesNotThrow(() -> source(EML).release());
    }

    @Test
    void materializeCopiesToTarget() throws IOException {
        StreamMessageSource source = source(EML);
        Path target = Files.createTempFile("robin-materialize-", ".eml");

        try {
            assertEquals(target, source.materialize(target));
            assertEquals(EML, Files.readString(target));

            // Materializing must not consume the source.
            assertEquals(EML, new String(source.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(target);
            source.release();
        }
    }

    @Test
    void envelopeStreamSurvivesMultipleConsumers() throws IOException {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setStream(new ByteArrayInputStream(EML.getBytes(StandardCharsets.UTF_8)));

        // Mirrors the server path: scan, then archive, then webhook each read independently.
        try (InputStream scan = envelope.openMessageStream()) {
            assertEquals(EML, new String(scan.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(EML, new String(envelope.readMessageBytes(), StandardCharsets.UTF_8));
        try (InputStream webhook = envelope.openMessageStream()) {
            assertEquals(EML, new String(webhook.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
