package com.mimecast.robin.smtp.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlowTransferOutputStream.
 */
class SlowTransferOutputStreamTest {

    private ByteArrayOutputStream delegate;
    private String testIp;

    @BeforeEach
    void setUp() {
        delegate = new ByteArrayOutputStream();
        testIp = "192.168.1.100";
        ConnectionTracker.reset();
    }

    @Test
    void testNormalTransfer() throws IOException {
        int minRate = 1000; // 1 KB/s
        int maxTimeout = 10; // 10 seconds

        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, minRate, maxTimeout, testIp
        );

        // Write 5 KB quickly
        byte[] data = new byte[5000];
        stream.write(data);
        stream.flush();
        stream.close();

        // Should not detect slow transfer
        assertFalse(stream.isSlowTransferDetected());
        assertEquals(5000, delegate.size());
    }

    @Test
    void testSlowTransferDetectionDisabled() throws IOException {
        // Both rate and timeout disabled (0)
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 0, 0, testIp
        );

        // Write slowly
        for (int i = 0; i < 100; i++) {
            stream.write(1);
        }
        stream.flush();
        stream.close();

        // Should not detect slow transfer when disabled
        assertFalse(stream.isSlowTransferDetected());
        assertEquals(100, delegate.size());
    }

    @Test
    void testWriteSingleByte() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write(65); // 'A'
        stream.flush();

        assertEquals(1, delegate.size());
        assertEquals(65, delegate.toByteArray()[0]);
    }

    @Test
    void testWriteByteArray() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        byte[] data = "Hello World".getBytes();
        stream.write(data);
        stream.flush();

        assertEquals(data.length, delegate.size());
        assertArrayEquals(data, delegate.toByteArray());
    }

    @Test
    void testWriteByteArrayWithOffset() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        byte[] data = "Hello World".getBytes();
        stream.write(data, 6, 5); // Write "World"
        stream.flush();

        assertEquals(5, delegate.size());
        assertEquals("World", new String(delegate.toByteArray()));
    }

    @Test
    void testGetBytesWritten() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        assertEquals(0, stream.getBytesWritten());

        stream.write(new byte[100]);
        assertEquals(100, stream.getBytesWritten());

        stream.write(new byte[50]);
        assertEquals(150, stream.getBytesWritten());
    }

    @Test
    void testGetElapsedSeconds() throws IOException, InterruptedException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        long start = stream.getElapsedSeconds();
        assertEquals(0, start);

        Thread.sleep(1100); // Sleep 1.1 seconds

        long elapsed = stream.getElapsedSeconds();
        assertTrue(elapsed >= 1, "Expected at least 1 second elapsed, got " + elapsed);
    }

    @Test
    void testAbsoluteTimeoutDisabled() throws IOException, InterruptedException {
        // Timeout disabled with 0
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 0, testIp
        );

        // Write very slowly but timeout is disabled
        Thread.sleep(100);
        stream.write(1);

        // Should not throw
        assertFalse(stream.isSlowTransferDetected());
    }

    @Test
    void testMinRateDisabled() throws IOException {
        // Min rate disabled with 0
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 0, 10, testIp
        );

        // Write very little data
        stream.write(1);

        // Should not detect slow transfer
        assertFalse(stream.isSlowTransferDetected());
    }

    @Test
    void testFlush() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write("test".getBytes());
        stream.flush();

        // Verify data was written to delegate
        assertEquals("test", new String(delegate.toByteArray()));
    }

    @Test
    void testClose() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write("test".getBytes());
        stream.close();

        // Verify data was written
        assertEquals("test", new String(delegate.toByteArray()));

        // ByteArrayOutputStream doesn't throw on second close
        assertDoesNotThrow(stream::close);
    }

    @Test
    void testConnectionTrackerIntegration() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        byte[] data = new byte[5000];
        stream.write(data);

        // Note: ConnectionTracker.recordBytesTransferred is called on rate check failure
        // In normal operation, it may not be called if rate is acceptable
        assertEquals(5000, stream.getBytesWritten());
    }

    @Test
    void testMultipleWrites() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write("Hello ".getBytes());
        stream.write("World".getBytes());
        stream.write("!".getBytes());

        assertEquals("Hello World!", new String(delegate.toByteArray()));
        assertEquals(12, stream.getBytesWritten());
    }

    @Test
    void testLargeTransfer() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 10000, 10, testIp // 10 KB/s minimum
        );

        // Write 50 KB quickly
        byte[] data = new byte[50000];
        stream.write(data);
        stream.flush();
        stream.close();

        assertEquals(50000, delegate.size());
        assertEquals(50000, stream.getBytesWritten());
        assertFalse(stream.isSlowTransferDetected());
    }

    @Test
    void testZeroByteWrite() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write(new byte[0]);

        assertEquals(0, stream.getBytesWritten());
        assertEquals(0, delegate.size());
    }

    @Test
    void testNullByteArrayThrows() {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        assertThrows(NullPointerException.class, () -> stream.write(null));
    }

    @Test
    void testInvalidOffsetThrows() {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        byte[] data = new byte[10];

        // Offset beyond array length
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream.write(data, 20, 5));

        // Negative offset
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream.write(data, -1, 5));
    }

    @Test
    void testInvalidLengthThrows() {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        byte[] data = new byte[10];

        // Length exceeds array size
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream.write(data, 0, 20));

        // Negative length
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream.write(data, 0, -1));
    }

    @Test
    void testConsecutiveCloses() throws IOException {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        stream.write("test".getBytes());
        stream.close();

        // Multiple closes should not throw
        assertDoesNotThrow(stream::close);
        assertDoesNotThrow(stream::close);
    }

    @Test
    void testSlowTransferNotDetectedInitially() {
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, testIp
        );

        // Before any transfer
        assertFalse(stream.isSlowTransferDetected());
    }

    @Test
    void testVeryHighMinRate() throws IOException {
        // Unrealistically high minimum rate
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000000000, 10, testIp // 1 GB/s
        );

        // Write 1 KB - should be way too slow
        stream.write(new byte[1024]);

        // Note: Won't detect as slow until after grace period (5 seconds)
        // and rate check interval
    }

    @Test
    void testVeryLongTimeout() throws IOException {
        // Very long timeout
        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 86400, testIp // 24 hours
        );

        stream.write(new byte[100]);

        // Should not timeout
        assertFalse(stream.isSlowTransferDetected());
    }

    @Test
    void testDifferentIpAddresses() throws IOException {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";

        ByteArrayOutputStream delegate1 = new ByteArrayOutputStream();
        ByteArrayOutputStream delegate2 = new ByteArrayOutputStream();

        SlowTransferOutputStream stream1 = new SlowTransferOutputStream(
            delegate1, 1000, 10, ip1
        );

        SlowTransferOutputStream stream2 = new SlowTransferOutputStream(
            delegate2, 1000, 10, ip2
        );

        stream1.write("IP1".getBytes());
        stream2.write("IP2".getBytes());

        assertEquals("IP1", new String(delegate1.toByteArray()));
        assertEquals("IP2", new String(delegate2.toByteArray()));
    }

    @Test
    void testIpv6Address() throws IOException {
        String ipv6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

        SlowTransferOutputStream stream = new SlowTransferOutputStream(
            delegate, 1000, 10, ipv6
        );

        stream.write("test".getBytes());

        assertEquals("test", new String(delegate.toByteArray()));
    }
}

