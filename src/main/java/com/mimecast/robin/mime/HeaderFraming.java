package com.mimecast.robin.mime;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Constant-state validation of raw header line endings and controls, without rewriting bytes.
 * Validation stops at the first empty LF or CRLF line; body bytes are opaque.
 */
public final class HeaderFraming {
    private boolean pendingCr;
    private boolean lineHasData;
    private boolean complete;
    private InvalidHeaderException failure;

    /**
     * Signals prohibited raw header framing. Messages contain no message content.
     */
    public static final class InvalidHeaderException extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidHeaderException(String reason) {
            super(reason);
        }
    }

    /**
     * Checks bytes from a header/message chunk, retaining state across chunk boundaries.
     *
     * @param bytes Raw, unchanged message bytes.
     * @param offset Start offset.
     * @param length Number of bytes.
     * @throws InvalidHeaderException Prohibited header framing.
     */
    public void accept(byte[] bytes, int offset, int length) throws InvalidHeaderException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (failure != null) throw failure;
        for (int i = offset; i < offset + length && !complete; i++) {
            accept(bytes[i] & 0xff);
        }
    }

    private void accept(int value) throws InvalidHeaderException {
        if (pendingCr) {
            if (value != '\n') {
                throw fail("Standalone CR in message headers");
            }
            pendingCr = false;
        } else if (value == '\r') {
            pendingCr = true;
            return;
        }
        if (value == '\n') {
            if (!lineHasData) complete = true;
            lineHasData = false;
        } else {
            if ((value < 0x20 && value != '\t') || value == 0x7f) {
                throw fail("Prohibited control byte in message headers");
            }
            lineHasData = true;
        }
    }

    /**
     * Checks end-of-message (not end-of-BDAT-chunk). Unambiguous header-only input is allowed.
     *
     * @throws InvalidHeaderException A previous failure or a trailing standalone CR.
     */
    public void finish() throws InvalidHeaderException {
        if (failure != null) throw failure;
        if (pendingCr) throw fail("Standalone CR at end of message headers");
    }

    private InvalidHeaderException fail(String reason) {
        failure = new InvalidHeaderException(reason);
        return failure;
    }

    /**
     * Wraps an output without changing bytes or ending validation on close (chunks may follow).
     *
     * @param delegate Receipt output owned by the caller.
     * @return Validating output; closing it closes the delegate.
     */
    public OutputStream receivingStream(OutputStream delegate) {
        return new FilterOutputStream(delegate) {
            @Override
            public void write(int value) throws IOException {
                if (failure != null) throw failure;
                if (!complete) accept(value & 0xff);
                out.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                accept(bytes, offset, length);
                out.write(bytes, offset, length);
            }
        };
    }

    /**
     * Validates headers from a fresh stream using bounded working memory, without closing it.
     *
     * @param input Raw message source.
     * @throws IOException Invalid framing or an input failure.
     */
    public static void validate(InputStream input) throws IOException {
        HeaderFraming framing = new HeaderFraming();
        byte[] buffer = new byte[8192];
        int read;
        while (!framing.complete && (read = input.read(buffer)) != -1) {
            framing.accept(buffer, 0, read);
        }
        framing.finish();
    }
}
