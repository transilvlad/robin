package com.mimecast.robin.mime.parts;

import com.google.common.primitives.Bytes;
import com.mimecast.robin.mime.headers.MimeHeader;

import java.io.*;
import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * MIME part container from file path.
 */
public class FileMimePart extends MimePart implements Closeable {

    /**
     * Append random bytes to the end of the file.
     */
    protected int appendRandomBytes = 0;

    /**
     * Default buffer size.
     */
    protected int defaultBufferSize = 8192;

    /**
     * File instance if any.
     */
    protected File file;
    private boolean closed;
    private boolean ownsFile;

    /**
     * Constructs a new FileMimePart instance.
     */
    public FileMimePart() {
        // Do nothing.
    }

    /**
     * Constructs a new FileMimePart instance with given path.
     *
     * @param file File instance.
     * @throws IOException Unable to open/read from file.
     */
    public FileMimePart(File file) throws IOException {
        this(file.getPath(), 8192);
    }

    /**
     * Constructs a new FileMimePart instance with given path.
     *
     * @param path Path to file.
     * @throws IOException Unable to open/read from file.
     */
    public FileMimePart(String path) throws IOException {
        this(path, 8192);
    }

    /**
     * Constructs a new FileMimePart instance with given path and buffer size.
     *
     * @param path       Path to file.
     * @param bufferSize Buffer size.
     * @throws IOException Unable to open/read from file.
     */
    public FileMimePart(String path, int bufferSize) throws IOException {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        file = new File(path);
        defaultBufferSize = bufferSize;
        try (InputStream ignored = new FileInputStream(file)) {
            // Validate at construction without retaining a file handle.
        }
    }

    /**
     * Takes ownership of a temporary file, deleting it when the part is closed.
     * Ordinary constructors borrow their source and never delete it.
     *
     * @param file Temporary backing file.
     * @return An owned file part.
     * @throws IOException Unable to open the file.
     */
    public static FileMimePart temporary(File file) throws IOException {
        FileMimePart part = new FileMimePart(file);
        part.ownsFile = true;
        return part;
    }

    /**
     * Sets how many random bytes to append at the end of the file.
     *
     * @param size Number of random bytes to append.
     * @return Self.
     */
    public FileMimePart setAppendRandomBytes(int size) {
        appendRandomBytes = size;
        return this;
    }

    /**
     * Writes email to given output stream.
     *
     * @param outputStream OutputStream instance.
     * @return Self.
     * @throws IOException Unable to open/read from file or write to output stream.
     */
    @Override
    public MimePart writeTo(OutputStream outputStream) throws IOException {
        // Ensure we have a Content-Type header.
        MimeHeader contentType = getHeader("Content-Type");
        if (contentType == null) {
            headers.put(new MimeHeader("Content-Type", "application/octet-stream"));
        }

        super.writeTo(outputStream);

        return this;
    }

    /**
     * Gets content as bytes.
     *
     * @return String.
     * @throws IOException Unable to read stream.
     */
    @Override
    public synchronized byte[] getBytes() throws IOException {
        if (closed || file == null) {
            throw new IOException("File MIME part is closed or has no backing file");
        }
        byte[] bytes;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), defaultBufferSize)) {
            bytes = input.readAllBytes();
        }

        if (appendRandomBytes > 0) {
            byte[] randomBytes = new byte[appendRandomBytes];
            new SecureRandom().nextBytes(randomBytes);

            bytes = Bytes.concat(bytes, randomBytes);
        }

        return bytes;
    }

    /**
     * Gets the file instance if any.
     *
     * @return File instance or null.
     */
    public File getFile() {
        return file;
    }

    /**
     * Closes this part and deletes its backing file only if ownership was transferred
     * through {@link #temporary(File)}. Repeated calls retry only a failed deletion;
     * successful deletion or an already absent path relinquishes ownership.
     *
     * @throws IOException If closing the stream fails.
     */
    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (body != null) {
            body.close();
        }
        if (ownsFile) {
            Files.deleteIfExists(file.toPath());
            ownsFile = false;
        }
    }
}
