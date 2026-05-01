package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.InMemoryMessageSource;
import com.mimecast.robin.smtp.MessageSource;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Buffers message receipt in memory and spills to disk after a configured threshold.
 */
public class MessageBufferOutputStream extends OutputStream {
    private final long thresholdBytes;
    private final Path spillFile;

    private ByteArrayOutputStream memoryStream = new ByteArrayOutputStream();
    private OutputStream activeStream = memoryStream;
    private long size;
    private boolean closed;

    /**
     * Constructs a new buffered output stream.
     *
     * @param thresholdBytes Spill threshold.
     * @param spillFile      Spill target file.
     */
    public MessageBufferOutputStream(long thresholdBytes, Path spillFile) {
        this.thresholdBytes = Math.max(0, thresholdBytes);
        this.spillFile = spillFile;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        spillIfNeeded(1);
        activeStream.write(b);
        size++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (b == null) {
            throw new NullPointerException("bytes");
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length");
        }
        if (len == 0) {
            return;
        }

        spillIfNeeded(len);
        activeStream.write(b, off, len);
        size += len;
    }

    @Override
    public void flush() throws IOException {
        activeStream.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        activeStream.close();
        closed = true;
    }

    /**
     * Gets the buffered size.
     *
     * @return Size in bytes.
     */
    public long size() {
        return size;
    }

    /**
     * Converts the current buffer into a canonical message source.
     * <p>For file-backed messages, returns a {@link RefCountedFileMessageSource}
     * to support safe concurrent access with automatic cleanup.
     *
     * @return MessageSource instance.
     */
    public MessageSource toMessageSource() {
        if (isSpilledToFile()) {
            return new RefCountedFileMessageSource(spillFile);
        }
        return new InMemoryMessageSource(memoryStream.toByteArray());
    }

    /**
     * Checks if the stream spilled to a file.
     *
     * @return Boolean.
     */
    public boolean isSpilledToFile() {
        return activeStream != memoryStream;
    }

    /**
     * Forces the buffer to spill to file regardless of threshold.
     * <p>Useful for bot processing where file-based access is required
     * for thread-safe concurrent access to the message content.
     *
     * @throws IOException If an I/O error occurs.
     */
    public void forceSpillToFile() throws IOException {
        if (isSpilledToFile() || size == 0) {
            return;
        }

        Path parent = spillFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        OutputStream fileStream = Files.newOutputStream(spillFile);
        memoryStream.writeTo(fileStream);
        activeStream = fileStream;
        memoryStream.reset();
    }

    private void spillIfNeeded(int nextWriteBytes) throws IOException {
        if (isSpilledToFile()) {
            return;
        }
        if (size + nextWriteBytes <= thresholdBytes) {
            return;
        }

        Path parent = spillFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        OutputStream fileStream = Files.newOutputStream(spillFile);
        memoryStream.writeTo(fileStream);
        activeStream = fileStream;
        memoryStream.reset();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}
