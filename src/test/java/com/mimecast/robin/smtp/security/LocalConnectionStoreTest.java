package com.mimecast.robin.smtp.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LocalConnectionStore.
 */
class LocalConnectionStoreTest {

    private LocalConnectionStore store;

    @BeforeEach
    void setUp() {
        store = new LocalConnectionStore();
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    void testRecordConnection() {
        store.recordConnection("192.168.1.100");

        assertEquals(1, store.getActiveConnections("192.168.1.100"));
        assertEquals(1, store.getTotalActiveConnections());
    }

    @Test
    void testRecordDisconnection() {
        store.recordConnection("192.168.1.100");
        store.recordDisconnection("192.168.1.100");

        assertEquals(0, store.getActiveConnections("192.168.1.100"));
        assertEquals(0, store.getTotalActiveConnections());
    }

    @Test
    void testMultipleConnectionsSameIp() {
        store.recordConnection("192.168.1.100");
        store.recordConnection("192.168.1.100");
        store.recordConnection("192.168.1.100");

        assertEquals(3, store.getActiveConnections("192.168.1.100"));
        assertEquals(3, store.getTotalActiveConnections());
    }

    @Test
    void testMultipleConnectionsDifferentIps() {
        store.recordConnection("10.0.0.1");
        store.recordConnection("10.0.0.1");
        store.recordConnection("10.0.0.2");
        store.recordConnection("10.0.0.3");

        assertEquals(2, store.getActiveConnections("10.0.0.1"));
        assertEquals(1, store.getActiveConnections("10.0.0.2"));
        assertEquals(1, store.getActiveConnections("10.0.0.3"));
        assertEquals(4, store.getTotalActiveConnections());
    }

    @Test
    void testDisconnectNonExistentConnection() {
        assertDoesNotThrow(() -> store.recordDisconnection("10.0.0.1"));
        assertEquals(0, store.getActiveConnections("10.0.0.1"));
    }

    @Test
    void testGetRecentConnectionCount() {
        for (int i = 0; i < 5; i++) {
            store.recordConnection("10.0.0.1");
        }
        assertEquals(5, store.getRecentConnectionCount("10.0.0.1", 60));
    }

    @Test
    void testGetRecentConnectionCountNoHistory() {
        assertEquals(0, store.getRecentConnectionCount("10.0.0.1", 60));
    }

    @Test
    void testRecordCommand() {
        store.recordCommand("10.0.0.1");
        store.recordCommand("10.0.0.1");
        store.recordCommand("10.0.0.1");

        assertTrue(store.getCommandsPerMinute("10.0.0.1") >= 3);
    }

    @Test
    void testGetCommandsPerMinuteNoCommands() {
        assertEquals(0, store.getCommandsPerMinute("10.0.0.1"));
    }

    @Test
    void testRecordBytesTransferred() {
        assertDoesNotThrow(() -> {
            store.recordBytesTransferred("10.0.0.1", 1024);
            store.recordBytesTransferred("10.0.0.1", 2048);
        });
    }

    @Test
    void testReset() {
        store.recordConnection("10.0.0.1");
        store.recordConnection("10.0.0.2");
        assertEquals(2, store.getTotalActiveConnections());

        store.reset();

        assertEquals(0, store.getTotalActiveConnections());
        assertEquals(0, store.getActiveConnections("10.0.0.1"));
        assertEquals(0, store.getActiveConnections("10.0.0.2"));
    }

    @Test
    void testNullIpHandling() {
        assertDoesNotThrow(() -> store.recordConnection(null));
        assertDoesNotThrow(() -> store.recordDisconnection(null));
        assertDoesNotThrow(() -> store.recordCommand(null));

        assertEquals(0, store.getActiveConnections(null));
        assertEquals(0, store.getRecentConnectionCount(null, 60));
        assertEquals(0, store.getCommandsPerMinute(null));
        assertEquals(0, store.getTotalActiveConnections());
    }

    @Test
    void testDisconnectMoreThanConnected() {
        store.recordConnection("10.0.0.1");
        store.recordConnection("10.0.0.1");

        store.recordDisconnection("10.0.0.1");
        store.recordDisconnection("10.0.0.1");
        store.recordDisconnection("10.0.0.1"); // Extra

        assertTrue(store.getActiveConnections("10.0.0.1") >= 0, "Active count must not go negative");
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        String ip = "10.0.0.1";
        int threadCount = 10;
        int connectionsPerThread = 10;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < connectionsPerThread; j++) {
                    store.recordConnection(ip);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(threadCount * connectionsPerThread, store.getActiveConnections(ip));
    }

    @Test
    void testIpv6Address() {
        String ipv6 = "2001:db8::1";
        store.recordConnection(ipv6);
        store.recordConnection(ipv6);

        assertEquals(2, store.getActiveConnections(ipv6));
    }
}
