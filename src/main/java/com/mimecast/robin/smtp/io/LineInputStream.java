package com.mimecast.robin.smtp.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/**
 * Input stream with binary line reading capability.
 *
 * <p>InputStream implementation returns lines with EOL as byte array and counts lines.
 */
public class LineInputStream extends PushbackInputStream {
    private static final Logger log = LogManager.getLogger(LineInputStream.class);

    /**
     * Carrige return byte.
     */
    private static final int CR = 13; // \r

    /**
     * Line feed byte.
     */
    private static final int LF = 10; // \n

    /**
     * Default internal read buffer size (1KB - safe for default pushback buffer).
     * Must be smaller than pushback buffer to avoid "Push back buffer is full" errors.
     */
    private static final int DEFAULT_BUFFER_SIZE = 1024;

    /**
     * Initial line buffer size (typical SMTP line is 78-998 bytes).
     */
    private static final int LINE_BUFFER_INITIAL_SIZE = 1024;

    /**
     * Current line number.
     */
    private int lineNumber = 0;

    /**
     * Pushback buffer size from constructor (used to size internal read buffer).
     */
    private final int pushbackSize;

    /**
     * Internal read buffer for bulk reads.
     * Size is limited to 75% of pushback buffer to ensure unread() always succeeds.
     */
    private final byte[] readBuffer;

    /**
     * Current position in read buffer.
     */
    private int bufferPos = 0;

    /**
     * Number of valid bytes in read buffer.
     */
    private int bufferLimit = 0;

    /**
     * Reusable line buffer to reduce allocations.
     */
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(LINE_BUFFER_INITIAL_SIZE);

    /**
     * Flag to prevent recursive buffer pushback when PushbackInputStream delegates between unread() methods.
     * Set to true during unread operations to avoid pushing back buffer data multiple times.
     */
    private boolean inUnreadOperation = false;

    /**
     * Constructs a new LineInputStream instance.
     * Uses default pushback buffer size (1 byte from PushbackInputStream).
     * For optimal performance, use constructor with explicit size.
     *
     * @param stream InputStream instance.
     */
    public LineInputStream(InputStream stream) {
        super(stream);
        this.pushbackSize = 1; // PushbackInputStream default
        // Use safe default that won't overflow tiny pushback buffer.
        this.readBuffer = new byte[1];
    }

    /**
     * Constructs a new LineInputStream instance with given pushback buffer size.
     * Internal read buffer is sized at 50% of pushback buffer to ensure unread()
     * operations always succeed when pushing back buffered data + caller's line.
     *
     * @param stream InputStream instance.
     * @param size   Pushback buffer size.
     */
    public LineInputStream(InputStream stream, int size) {
        super(stream, size);
        this.pushbackSize = size;
        // Size read buffer to 50% of pushback buffer for safety margin.
        // This ensures we can always push back: unprocessed buffer data + caller's line.
        // Example: 1024 byte pushback → 512 byte read buffer, leaving 512 bytes for caller.
        int bufferSize = Math.max(256, size / 2);
        this.readBuffer = new byte[bufferSize];
    }

    /**
     * Gets line number.
     *
     * @return Line number.
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Read line as byte array.
     *
     * <p>Optimized implementation uses internal buffering to reduce overhead.
     * Falls back to per-byte reading if pushback buffer is active (after unread()).
     *
     * @return Byte array.
     * @throws IOException Unable to read.
     */
    @SuppressWarnings({"squid:S1168", "squid:S135"})
    public byte[] readLine() throws IOException {
        // Reset reusable line buffer.
        lineBuffer.reset();

        boolean foundCR = false;
        int intByte;

        // Main reading loop - uses buffered reads when possible.
        while (true) {
            // Refill internal buffer if needed.
            if (bufferPos >= bufferLimit) {
                bufferLimit = super.read(readBuffer, 0, readBuffer.length);
                bufferPos = 0;

                // End of stream.
                if (bufferLimit == -1) {
                    break;
                }
            }

            // Process bytes from internal buffer.
            while (bufferPos < bufferLimit) {
                intByte = readBuffer[bufferPos++] & 0xFF;

                // Have CR but LF doesn't follow - unread byte and return what is read so far.
                if (foundCR && intByte != LF) {
                    // Push back the byte by adjusting buffer position.
                    bufferPos--;
                    lineNumber++;
                    return lineBuffer.toByteArray();
                }

                // CR is recorded and read continues in hope of finding LF next.
                foundCR = foundCR || intByte == CR;

                // LF will instantly terminate the read and return.
                if (intByte == LF) {
                    lineBuffer.write(intByte);
                    lineNumber++;
                    return lineBuffer.toByteArray();
                }

                lineBuffer.write(intByte);
            }
        }

        // Return null if nothing was read.
        if (lineBuffer.size() == 0) {
            return null;
        }

        lineNumber++;
        return lineBuffer.toByteArray();
    }

    /**
     * Overrides read() to use internal buffer when possible.
     *
     * <p>Maintains compatibility with PushbackInputStream functionality.
     *
     * @return Byte read or -1 if end of stream.
     * @throws IOException Unable to read.
     */
    @Override
    public int read() throws IOException {
        // Use internal buffer if available.
        if (bufferPos < bufferLimit) {
            return readBuffer[bufferPos++] & 0xFF;
        }

        // Refill buffer.
        bufferLimit = super.read(readBuffer, 0, readBuffer.length);
        bufferPos = 0;

        if (bufferLimit == -1) {
            return -1;
        }

        return readBuffer[bufferPos++] & 0xFF;
    }

    /**
     * Overrides unread() to preserve internal buffer state.
     *
     * <p>CRITICAL: Before pushing back requested bytes, must first push back any
     * unprocessed data from internal buffer. Otherwise that data is lost forever,
     * causing MIME parts and boundaries to be missed during parsing.
     *
     * @param b Byte to unread.
     * @throws IOException Unable to unread.
     */
    @Override
    public void unread(int b) throws IOException {
        // Prevent recursive buffer pushback when called as delegate from PushbackInputStream.
        if (!inUnreadOperation) {
            inUnreadOperation = true;
            try {
                // Push back unprocessed buffer data first, then requested byte.
                if (bufferPos < bufferLimit) {
                    super.unread(readBuffer, bufferPos, bufferLimit - bufferPos);
                }
                super.unread(b);

                // Invalidate internal buffer.
                bufferPos = 0;
                bufferLimit = 0;
            } finally {
                inUnreadOperation = false;
            }
        } else {
            // Called recursively - just delegate without buffer management.
            super.unread(b);
        }
    }

    /**
     * Overrides unread() to preserve internal buffer state.
     *
     * <p>CRITICAL: Before pushing back requested bytes, must first push back any
     * unprocessed data from internal buffer. Otherwise that data is lost forever,
     * causing MIME parts and boundaries to be missed during parsing.
     *
     * @param b Byte array to unread.
     * @throws IOException Unable to unread.
     */
    @Override
    public void unread(byte[] b) throws IOException {
        // Prevent recursive buffer pushback when called as delegate from PushbackInputStream.
        if (!inUnreadOperation) {
            inUnreadOperation = true;
            try {
                // Push back unprocessed buffer data first, then requested bytes.
                if (bufferPos < bufferLimit) {
                    super.unread(readBuffer, bufferPos, bufferLimit - bufferPos);
                }
                super.unread(b);

                // Invalidate internal buffer.
                bufferPos = 0;
                bufferLimit = 0;
            } finally {
                inUnreadOperation = false;
            }
        } else {
            // Called recursively - just delegate without buffer management.
            super.unread(b);
        }
    }

    /**
     * Overrides unread() to preserve internal buffer state.
     *
     * <p>CRITICAL: Before pushing back requested bytes, must first push back any
     * unprocessed data from internal buffer. Otherwise that data is lost forever,
     * causing MIME parts and boundaries to be missed during parsing.
     *
     * @param b   Byte array to unread.
     * @param off Offset.
     * @param len Length.
     * @throws IOException Unable to unread.
     */
    @Override
    public void unread(byte[] b, int off, int len) throws IOException {
        // Prevent recursive buffer pushback when called as delegate from PushbackInputStream.
        if (!inUnreadOperation) {
            inUnreadOperation = true;
            try {
                // Push back unprocessed buffer data first, then requested bytes.
                if (bufferPos < bufferLimit) {
                    super.unread(readBuffer, bufferPos, bufferLimit - bufferPos);
                }
                super.unread(b, off, len);

                // Invalidate internal buffer.
                bufferPos = 0;
                bufferLimit = 0;
            } finally {
                inUnreadOperation = false;
            }
        } else {
            // Called recursively - just delegate without buffer management.
            super.unread(b, off, len);
        }
    }
}
