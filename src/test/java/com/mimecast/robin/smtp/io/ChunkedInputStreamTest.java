package com.mimecast.robin.smtp.io;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkedInputStreamTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    ChunkedInputStream getStream(MessageEnvelope envelope) throws IOException {
        return new ChunkedInputStream(
                new BufferedInputStream(new FileInputStream(envelope.getFile()), 8192),
                envelope
        );
    }

    @Test
    void getChunk256() throws IOException {
        MessageEnvelope envelope = getEnvelope();
        envelope.setChunkSize(256);

        ChunkedInputStream stream = getStream(envelope);
        List<ByteArrayOutputStream> chunks = new ArrayList<>();
        while(stream.hasChunks()) {
            chunks.add(stream.getChunk());
        }

        assertEquals(11, chunks.size());
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertEquals(256, chunks.get(i).size());
        }
        assertEquals(214, chunks.get(10).size());
    }

    @Test
    void getChunkZero() throws IOException {
        MessageEnvelope envelope = getEnvelope();
        envelope.setChunkSize(0); // Defaults to 2048 as size has to be > 0.

        ChunkedInputStream stream = getStream(envelope);
        List<ByteArrayOutputStream> chunks = new ArrayList<>();
        while(stream.hasChunks()) {
            chunks.add(stream.getChunk());
        }

        assertEquals(2, chunks.size());
        assertEquals(2048, chunks.get(0).size());
        assertEquals(726, chunks.get(1).size());
    }

    @Test
    void getChunkLess() throws IOException {
        MessageEnvelope envelope = getEnvelope();
        envelope.setChunkSize(100); // Defaults to 128 as size is under min viable limit of 128.

        ChunkedInputStream stream = getStream(envelope);
        List<ByteArrayOutputStream> chunks = new ArrayList<>();
        while(stream.hasChunks()) {
            chunks.add(stream.getChunk());
        }

        assertEquals(2, chunks.size());
        assertEquals(2048, chunks.get(0).size());
        assertEquals(726, chunks.get(1).size());
    }

    @Test
    void getChunkBreak() throws IOException {
        MessageEnvelope envelope = getEnvelope();
        envelope.setChunkSize(512);

        ChunkedInputStream stream = getStream(envelope);
        List<ByteArrayOutputStream> chunks = new ArrayList<>();
        while(stream.hasChunks()) {
            chunks.add(stream.getChunk(true));
        }

        assertEquals(6, chunks.size());
        assertEquals(240, chunks.get(0).size());
        assertEquals(512, chunks.get(1).size());
        assertEquals(512, chunks.get(2).size());
        assertEquals(512, chunks.get(3).size());
        assertEquals(512, chunks.get(4).size());
        assertEquals(486, chunks.get(5).size());
    }

    private MessageEnvelope getEnvelope() {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile("src/test/resources/mime/lipsum.eml");
        envelope.setChunkBdat(true);
        return envelope;
    }
}
