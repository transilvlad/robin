package com.mimecast.robin.smtp;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * In-memory envelope message source.
 */
public class InMemoryMessageSource implements MessageSource, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final byte[] bytes;

    /**
     * Constructs a new in-memory source from bytes.
     *
     * @param bytes Message bytes.
     */
    public InMemoryMessageSource(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public java.io.InputStream openStream() {
        return new java.io.ByteArrayInputStream(bytes);
    }

    @Override
    public byte[] readAllBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public long size() {
        return bytes.length;
    }

    @Override
    public Optional<Path> getMaterializedPath() {
        return Optional.empty();
    }

    @Override
    public Path materialize(Path targetFile) throws IOException {
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(targetFile, bytes);
        return targetFile;
    }
}
