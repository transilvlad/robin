package com.mimecast.robin.smtp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted file message source for safe concurrent access.
 * <p>This wrapper tracks the number of active consumers of a file-backed message.
 * The backing file is only deleted when all consumers have released their references.
 * <p>Usage:
 * <ul>
 *   <li>Call {@link #acquire()} before sharing with another consumer (e.g., bot threads)</li>
 *   <li>Call {@link #release()} when done processing (e.g., in Session.close())</li>
 *   <li>File is automatically deleted when reference count reaches zero</li>
 * </ul>
 * <p>Thread-safe: All reference count operations use atomic operations.
 */
public class RefCountedFileMessageSource extends FileMessageSource {
    private static final Logger log = LogManager.getLogger(RefCountedFileMessageSource.class);

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Reference count tracking active consumers.
     * Starts at 1 for the initial owner.
     */
    private transient AtomicInteger refCount = new AtomicInteger(1);

    /**
     * Constructs a reference-counted file source.
     *
     * @param path Message file path.
     */
    public RefCountedFileMessageSource(Path path) {
        super(path);
    }

    /**
     * Acquires a reference to this message source.
     * <p>Increments the reference count. The caller must ensure a matching
     * {@link #release()} call when done with the source.
     *
     * @return This message source for chaining.
     */
    @Override
    public MessageSource acquire() {
        int count = refCount.incrementAndGet();
        log.debug("Acquired reference to {}, count now: {}", getPath(), count);
        return this;
    }

    /**
     * Releases a reference to this message source.
     * <p>Decrements the reference count. When the count reaches zero,
     * the backing file is deleted.
     */
    @Override
    public void release() {
        int count = refCount.decrementAndGet();
        log.debug("Released reference to {}, count now: {}", getPath(), count);

        if (count == 0) {
            deleteFile();
        } else if (count < 0) {
            log.warn("Reference count went negative for {}, possible double-release", getPath());
        }
    }

    /**
     * Gets the current reference count.
     * <p>Primarily for testing and debugging.
     *
     * @return Current reference count.
     */
    public int getRefCount() {
        return refCount.get();
    }

    /**
     * Deletes the backing file.
     */
    private void deleteFile() {
        Path path = getPath();
        if (path == null) {
            return;
        }

        try {
            if (Files.deleteIfExists(path)) {
                log.debug("Deleted temporary file: {}", path);
            }
        } catch (IOException e) {
            log.error("Error deleting temporary file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Custom deserialization to reinitialize transient fields.
     * <p>On deserialization, the reference count is reset to 1 since
     * the deserializing consumer becomes the sole owner.
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        refCount = new AtomicInteger(1);
    }
}

