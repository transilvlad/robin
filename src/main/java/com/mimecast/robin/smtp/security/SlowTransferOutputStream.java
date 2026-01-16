package com.mimecast.robin.smtp.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Output stream wrapper that detects slow data transfer (slowloris attacks).
 *
 * <p>Tracks bytes written over time and enforces minimum transfer rate.
 * This stream wrapper is used during SMTP DATA/BDAT commands to prevent
 * slowloris-style DoS attacks where clients send data extremely slowly
 * to tie up server resources.
 *
 * <p>Two types of limits are enforced:
 * <ul>
 *   <li>Minimum transfer rate (bytes per second)</li>
 *   <li>Absolute maximum timeout (total transfer duration)</li>
 * </ul>
 *
 * <p>Transfer rate is checked every 5 seconds after an initial 5-second grace period.
 *
 * @see ConnectionTracker
 */
public class SlowTransferOutputStream extends OutputStream {
    private static final Logger log = LogManager.getLogger(SlowTransferOutputStream.class);

    private final OutputStream delegate;
    private final int minBytesPerSecond;
    private final int maxTimeoutSeconds;
    private final String clientIp;
    private final long startTime;
    private long bytesWritten = 0;
    private long lastCheckTime;
    private boolean slowTransferDetected = false;

    /**
     * Constructs a new SlowTransferOutputStream.
     *
     * @param delegate          The underlying output stream to wrap.
     * @param minBytesPerSecond Minimum transfer rate in bytes per second (0 to disable).
     * @param maxTimeoutSeconds Maximum total transfer time in seconds (0 to disable).
     * @param clientIp          Client IP address for logging.
     */
    public SlowTransferOutputStream(OutputStream delegate, int minBytesPerSecond, int maxTimeoutSeconds, String clientIp) {
        this.delegate = delegate;
        this.minBytesPerSecond = minBytesPerSecond;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
        this.clientIp = clientIp;
        this.startTime = System.currentTimeMillis();
        this.lastCheckTime = startTime;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        bytesWritten++;
        checkTransferRate();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        bytesWritten += len;
        checkTransferRate();
    }

    @Override
    public void write(byte[] b) throws IOException {
        delegate.write(b);
        bytesWritten += b.length;
        checkTransferRate();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /**
     * Checks if transfer rate or timeout limits have been exceeded.
     *
     * @throws IOException If slow transfer or timeout is detected.
     */
    private void checkTransferRate() throws IOException {
        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - startTime) / 1000;

        // Check absolute timeout.
        if (maxTimeoutSeconds > 0 && elapsedSeconds > maxTimeoutSeconds) {
            slowTransferDetected = true;
            log.warn("Slow transfer timeout exceeded for {}: {}s elapsed", clientIp, elapsedSeconds);
            ConnectionTracker.recordBytesTransferred(clientIp, bytesWritten);
            throw new IOException("Data transfer timeout exceeded");
        }

        // Check transfer rate every 5 seconds after initial grace period.
        if (minBytesPerSecond > 0 && (currentTime - lastCheckTime) >= 5000 && elapsedSeconds >= 5) {
            long expectedBytes = elapsedSeconds * minBytesPerSecond;
            if (bytesWritten < expectedBytes) {
                slowTransferDetected = true;
                double actualRate = (double) bytesWritten / elapsedSeconds;
                log.warn("Slow transfer detected for {}: {} bytes in {}s ({} B/s, minimum: {} B/s)",
                        clientIp, bytesWritten, elapsedSeconds, (int) actualRate, minBytesPerSecond);
                ConnectionTracker.recordBytesTransferred(clientIp, bytesWritten);
                throw new IOException("Transfer rate too slow");
            }
            lastCheckTime = currentTime;
        }
    }

    /**
     * Gets whether slow transfer was detected.
     *
     * @return True if slow transfer was detected, false otherwise.
     */
    public boolean isSlowTransferDetected() {
        return slowTransferDetected;
    }

    /**
     * Gets the number of bytes written so far.
     *
     * @return Bytes written.
     */
    public long getBytesWritten() {
        return bytesWritten;
    }

    /**
     * Gets the elapsed time in seconds since stream creation.
     *
     * @return Elapsed seconds.
     */
    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}

