package com.mimecast.robin.storage;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.connection.Connection;

import java.io.IOException;

/**
 * Abstract base class for storage processors.
 *
 * <p>Provides common chaos header handling logic. Subclasses only need to implement
 * the {@link #processInternal(Connection, EmailParser)} method with their specific logic.
 *
 * <p>The {@link #process(Connection, EmailParser)} method checks for chaos headers first.
 * If a chaos header forces a return value, that value is returned immediately.
 * Otherwise, the {@link #processInternal(Connection, EmailParser)} method is called.
 */
public abstract class AbstractStorageProcessor implements StorageProcessor {

    /**
     * Processes storage for the given session.
     *
     * <p>Checks for chaos headers that force a specific return value.
     * If found, returns the forced value immediately without calling processInternal().
     * Otherwise, delegates to processInternal() for normal processing.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return Boolean.
     * @throws IOException On I/O error.
     */
    @Override
    public final boolean process(Connection connection, EmailParser emailParser) throws IOException {
        // Check for chaos headers that force a specific return value.
        var forcedValue = getForcedReturnValue(emailParser);
        if (forcedValue.isPresent()) {
            return forcedValue.get();
        }

        // No forced value, proceed with normal processing.
        return processInternal(connection, emailParser);
    }

    /**
     * Internal processing logic to be implemented by subclasses.
     *
     * <p>This method is called only if no chaos header forces a return value.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return Boolean.
     * @throws IOException On I/O error.
     */
    protected abstract boolean processInternal(Connection connection, EmailParser emailParser) throws IOException;
}
