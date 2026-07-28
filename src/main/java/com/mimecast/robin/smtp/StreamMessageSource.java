package com.mimecast.robin.smtp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Stream-backed envelope message source.
 *
 * <p>Wraps a one-shot {@link InputStream} and makes it satisfy the repeatable
 * {@link MessageSource} contract. The stream is spooled to a temporary file on first
 * access; every read after that re-opens the spool file, so the message can be consumed
 * any number of times by independent consumers (AV scan, spam scan, DKIM signing,
 * archive, webhook) without one of them exhausting it for the others.
 *
 * <p>Spooling is lazy: an envelope whose payload is never read costs nothing. Spooling
 * to disk rather than to a byte array keeps memory bounded for large messages.
 *
 * <p>Call {@link #release()} to delete the spool file when the envelope is done with.
 * <p>Thread-safe: spooling is guarded so concurrent first-readers spool exactly once.
 */
public class StreamMessageSource implements MessageSource, Serializable {
    private static final Logger log = LogManager.getLogger(StreamMessageSource.class);

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * One-shot source stream, consumed and dropped once spooled.
     */
    private transient InputStream stream;

    /**
     * Spool file path, set once the stream has been materialized.
     */
    private volatile String spoolPath;

    /**
     * Constructs a stream-backed source.
     *
     * @param stream Message stream.
     */
    public StreamMessageSource(InputStream stream) {
        this.stream = stream;
    }

    /**
     * Spools the one-shot stream to a temporary file, once.
     *
     * @return Spool file path.
     * @throws IOException On I/O error, or if the source has no content left to spool.
     */
    private synchronized Path spool() throws IOException {
        if (spoolPath != null) {
            return Path.of(spoolPath);
        }

        if (stream == null) {
            throw new IOException("Stream message source has no content to read");
        }

        Path path = Files.createTempFile("robin-envelope-", ".eml");
        try (InputStream in = stream) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        } finally {
            // Drop the reference either way; a partially consumed stream cannot be reused.
            stream = null;
        }

        spoolPath = path.toString();
        log.debug("Spooled envelope stream to {}", path);
        return path;
    }

    @Override
    public InputStream openStream() throws IOException {
        return Files.newInputStream(spool());
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return Files.readAllBytes(spool());
    }

    @Override
    public long size() throws IOException {
        return Files.size(spool());
    }

    @Override
    public Optional<Path> getMaterializedPath() {
        return spoolPath == null ? Optional.empty() : Optional.of(Path.of(spoolPath));
    }

    @Override
    public Path materialize(Path targetFile) throws IOException {
        Path path = spool();
        if (path.equals(targetFile)) {
            return targetFile;
        }

        Files.copy(path, targetFile, StandardCopyOption.REPLACE_EXISTING);
        return targetFile;
    }

    /**
     * Deletes the spool file, if one was created.
     */
    @Override
    public void release() {
        if (spoolPath == null) {
            return;
        }

        try {
            if (Files.deleteIfExists(Path.of(spoolPath))) {
                log.debug("Deleted envelope spool file: {}", spoolPath);
            }
        } catch (IOException e) {
            log.error("Error deleting envelope spool file {}: {}", spoolPath, e.getMessage());
        }
    }
}
