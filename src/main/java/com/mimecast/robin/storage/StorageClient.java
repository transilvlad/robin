package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.connection.Connection;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Server file storage interface.
 *
 * <p>The instantiation of this will be done via Factories.
 * <p>Connection is required to allow customisation based on sender/recipient.
 */
public interface StorageClient {

    /**
     * Sets connection.
     *
     * @param connection Connection instance.
     * @return Self.
     */
    StorageClient setConnection(Connection connection);

    /**
     * Sets extension.
     *
     * @param extension File extension.
     * @return Self.
     */
    StorageClient setExtension(String extension);

    /**
     * Gets file output stream.
     *
     * @return OutputStream instance.
     * @throws FileNotFoundException File not found.
     */
    OutputStream getStream() throws FileNotFoundException;

    /**
     * Gets path.
     *
     * @return String.
     */
    String getPath();

    /**
     * Gets file path.
     *
     * @return String.
     */
    String getFile();

    /**
     * Saves file.
     *
     * @return Boolean.
     */
    boolean save();

    /**
     * Closes an unaccepted receipt. Implementations owning temporary storage must also discard it.
     *
     * @throws IOException Unable to close or discard the receipt.
     */
    default void abort() throws IOException {
        getStream().close();
    }
}
