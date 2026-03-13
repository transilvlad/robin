package com.mimecast.robin.smtp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * File-backed envelope message source.
 */
public class FileMessageSource implements MessageSource, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;

    /**
     * Constructs a file-backed source.
     *
     * @param path Message file path.
     */
    public FileMessageSource(Path path) {
        this.path = path == null ? null : path.toString();
    }

    /**
     * Gets source path.
     *
     * @return Path or null.
     */
    public Path getPath() {
        return path == null ? null : Path.of(path);
    }

    @Override
    public InputStream openStream() throws IOException {
        return Files.newInputStream(getPath());
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return Files.readAllBytes(getPath());
    }

    @Override
    public long size() throws IOException {
        return Files.size(getPath());
    }

    @Override
    public Optional<Path> getMaterializedPath() {
        return Optional.ofNullable(getPath());
    }

    @Override
    public Path materialize(Path targetFile) throws IOException {
        Path sourcePath = getPath();
        if (sourcePath == null) {
            throw new IOException("No source path available");
        }

        Path normalizedSource = sourcePath.toAbsolutePath().normalize();
        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedTarget)) {
            return sourcePath;
        }

        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(sourcePath, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return targetFile;
    }
}
