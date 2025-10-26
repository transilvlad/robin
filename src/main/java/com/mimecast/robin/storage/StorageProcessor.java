package com.mimecast.robin.storage;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.connection.Connection;

import java.io.IOException;

/**
 * Storage processor interface.
 * <p>Used to process emails after receiving the message.
 */
public interface StorageProcessor {

    /**
     * Processes storage for the given session.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return Boolean.
     * @throws IOException On I/O error.
     */
    boolean process(Connection connection, EmailParser emailParser) throws IOException;
}
