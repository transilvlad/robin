package com.mimecast.robin.smtp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Canonical message source abstraction for an envelope payload.
 */
public interface MessageSource extends Serializable {

    /**
     * Acquires a reference to this message source.
     * <p>For reference-counted sources, this increments the reference count.
     * Callers must ensure a matching {@link #release()} call when done.
     *
     * @return This message source for chaining.
     */
    default MessageSource acquire() {
        return this;
    }

    /**
     * Releases a reference to this message source.
     * <p>For reference-counted file sources, this decrements the reference count
     * and deletes the backing file when the count reaches zero.
     * <p>For in-memory sources, this is a no-op.
     */
    default void release() {
        // Default no-op for sources that don't need cleanup.
    }

    /**
     * Opens a new input stream for the message contents.
     *
     * @return InputStream instance.
     * @throws IOException On I/O error.
     */
    InputStream openStream() throws IOException;

    /**
     * Reads the full message into memory.
     *
     * @return Message bytes.
     * @throws IOException On I/O error.
     */
    byte[] readAllBytes() throws IOException;

    /**
     * Gets the message size in bytes.
     *
     * @return Message size.
     * @throws IOException On I/O error.
     */
    long size() throws IOException;

    /**
     * Gets the materialized path if one already exists.
     *
     * @return Optional path.
     */
    Optional<Path> getMaterializedPath();

    /**
     * Materializes the message into the provided target file path.
     *
     * @param targetFile Target file path.
     * @return Materialized file path.
     * @throws IOException On I/O error.
     */
    Path materialize(Path targetFile) throws IOException;
}
