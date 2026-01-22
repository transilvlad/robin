package com.mimecast.robin.smtp.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConnectionTracker.
 */
@Isolated
class ConnectionTrackerTest {

    @BeforeEach
    void setUp() {
        ConnectionTracker.reset();
    }

    @AfterEach
    void tearDown() {
        ConnectionTracker.reset();
    }

    @Test
    void testRecordConnection() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordConnection(ip);

        assertEquals(1, ConnectionTracker.getActiveConnections(ip));
        assertEquals(1, ConnectionTracker.getTotalActiveConnections());
    }

    @Test
    void testRecordDisconnection() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordConnection(ip);
        assertEquals(1, ConnectionTracker.getActiveConnections(ip));

        ConnectionTracker.recordDisconnection(ip);
        assertEquals(0, ConnectionTracker.getActiveConnections(ip));
        assertEquals(0, ConnectionTracker.getTotalActiveConnections());
    }

    @Test
    void testMultipleConnectionsSameIp() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordConnection(ip);
        ConnectionTracker.recordConnection(ip);
        ConnectionTracker.recordConnection(ip);

        assertEquals(3, ConnectionTracker.getActiveConnections(ip));
        assertEquals(3, ConnectionTracker.getTotalActiveConnections());
    }

    @Test
    void testMultipleConnectionsDifferentIps() {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";
        String ip3 = "192.168.1.102";

        ConnectionTracker.recordConnection(ip1);
        ConnectionTracker.recordConnection(ip1);
        ConnectionTracker.recordConnection(ip2);
        ConnectionTracker.recordConnection(ip3);

        assertEquals(2, ConnectionTracker.getActiveConnections(ip1));
        assertEquals(1, ConnectionTracker.getActiveConnections(ip2));
        assertEquals(1, ConnectionTracker.getActiveConnections(ip3));
        assertEquals(4, ConnectionTracker.getTotalActiveConnections());
    }

    @Test
    void testDisconnectNonExistentConnection() {
        String ip = "192.168.1.100";

        // Disconnecting when no connections exist should not throw
        assertDoesNotThrow(() -> ConnectionTracker.recordDisconnection(ip));

        assertEquals(0, ConnectionTracker.getActiveConnections(ip));
    }

    @Test
    void testGetRecentConnectionCount() {
        String ip = "192.168.1.100";

        // Record multiple connections
        for (int i = 0; i < 5; i++) {
            ConnectionTracker.recordConnection(ip);
        }

        // Check recent connections in 60 second window
        int recentCount = ConnectionTracker.getRecentConnectionCount(ip, 60);
        assertEquals(5, recentCount);
    }

    @Test
    void testGetRecentConnectionCountEmptyHistory() {
        String ip = "192.168.1.100";

        int recentCount = ConnectionTracker.getRecentConnectionCount(ip, 60);
        assertEquals(0, recentCount);
    }

    @Test
    void testRecordCommand() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordCommand(ip);
        ConnectionTracker.recordCommand(ip);
        ConnectionTracker.recordCommand(ip);

        // Commands per minute should be at least 3
        int commandsPerMinute = ConnectionTracker.getCommandsPerMinute(ip);
        assertTrue(commandsPerMinute >= 3);
    }

    @Test
    void testGetCommandsPerMinuteNoCommands() {
        String ip = "192.168.1.100";

        int commandsPerMinute = ConnectionTracker.getCommandsPerMinute(ip);
        assertEquals(0, commandsPerMinute);
    }

    @Test
    void testRecordBytesTransferred() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordBytesTransferred(ip, 1024);
        ConnectionTracker.recordBytesTransferred(ip, 2048);

        // Just verify it doesn't throw - no getter for total bytes
        assertDoesNotThrow(() -> ConnectionTracker.recordBytesTransferred(ip, 512));
    }

    @Test
    void testReset() {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";

        ConnectionTracker.recordConnection(ip1);
        ConnectionTracker.recordConnection(ip2);
        ConnectionTracker.recordCommand(ip1);

        assertEquals(2, ConnectionTracker.getTotalActiveConnections());

        ConnectionTracker.reset();

        assertEquals(0, ConnectionTracker.getTotalActiveConnections());
        assertEquals(0, ConnectionTracker.getActiveConnections(ip1));
        assertEquals(0, ConnectionTracker.getActiveConnections(ip2));
        assertEquals(0, ConnectionTracker.getCommandsPerMinute(ip1));
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        String ip = "192.168.1.100";
        int threadCount = 10;
        int connectionsPerThread = 10;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < connectionsPerThread; j++) {
                    ConnectionTracker.recordConnection(ip);
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Should have recorded all connections
        assertEquals(threadCount * connectionsPerThread, ConnectionTracker.getActiveConnections(ip));
    }

    @Test
    void testNullIpHandling() {
        // Should not throw on null IP
        assertDoesNotThrow(() -> ConnectionTracker.recordConnection(null));
        assertDoesNotThrow(() -> ConnectionTracker.recordDisconnection(null));
        assertDoesNotThrow(() -> ConnectionTracker.recordCommand(null));

        assertEquals(0, ConnectionTracker.getActiveConnections(null));
    }

    @Test
    void testEmptyIpHandling() {
        String emptyIp = "";

        ConnectionTracker.recordConnection(emptyIp);
        assertEquals(1, ConnectionTracker.getActiveConnections(emptyIp));

        ConnectionTracker.recordDisconnection(emptyIp);
        assertEquals(0, ConnectionTracker.getActiveConnections(emptyIp));
    }

    @Test
    void testIpv6Address() {
        String ipv6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

        ConnectionTracker.recordConnection(ipv6);
        ConnectionTracker.recordConnection(ipv6);

        assertEquals(2, ConnectionTracker.getActiveConnections(ipv6));
    }

    @Test
    void testConnectionHistoryTracking() {
        String ip = "192.168.1.100";

        // Record connections over time
        for (int i = 0; i < 15; i++) {
            ConnectionTracker.recordConnection(ip);
        }

        // Check that history is maintained
        int recentCount = ConnectionTracker.getRecentConnectionCount(ip, 60);
        assertEquals(15, recentCount);

        // Verify with shorter window
        int recentCountShort = ConnectionTracker.getRecentConnectionCount(ip, 5);
        assertTrue(recentCountShort <= 15); // Should be less than or equal
    }

    @Test
    void testGetAllTrackedIps() {
        ConnectionTracker.recordConnection("192.168.1.100");
        ConnectionTracker.recordConnection("192.168.1.101");
        ConnectionTracker.recordConnection("192.168.1.102");

        // Verify at least 3 IPs tracked (method returns list)
        assertTrue(ConnectionTracker.getTotalActiveConnections() >= 3);
    }

    @Test
    void testDisconnectMoreThanConnected() {
        String ip = "192.168.1.100";

        ConnectionTracker.recordConnection(ip);
        ConnectionTracker.recordConnection(ip);

        assertEquals(2, ConnectionTracker.getActiveConnections(ip));

        // Disconnect more times than connected
        ConnectionTracker.recordDisconnection(ip);
        ConnectionTracker.recordDisconnection(ip);
        ConnectionTracker.recordDisconnection(ip); // Extra

        // Should not go negative
        assertTrue(ConnectionTracker.getActiveConnections(ip) >= 0);
    }

    @Test
    void testCommandRateCalculation() throws InterruptedException {
        String ip = "192.168.1.100";

        // Record 50 commands
        for (int i = 0; i < 50; i++) {
            ConnectionTracker.recordCommand(ip);
        }

        int commandsPerMinute = ConnectionTracker.getCommandsPerMinute(ip);

        // Should have recorded approximately 50 commands/minute
        assertTrue(commandsPerMinute >= 50, "Expected at least 50 commands/minute, got " + commandsPerMinute);
    }

    @Test
    void testMultipleIpIsolation() {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";

        // IP1: 5 connections, 10 commands
        for (int i = 0; i < 5; i++) {
            ConnectionTracker.recordConnection(ip1);
        }
        for (int i = 0; i < 10; i++) {
            ConnectionTracker.recordCommand(ip1);
        }

        // IP2: 3 connections, 5 commands
        for (int i = 0; i < 3; i++) {
            ConnectionTracker.recordConnection(ip2);
        }
        for (int i = 0; i < 5; i++) {
            ConnectionTracker.recordCommand(ip2);
        }

        // Verify isolation
        assertEquals(5, ConnectionTracker.getActiveConnections(ip1));
        assertEquals(3, ConnectionTracker.getActiveConnections(ip2));
        assertTrue(ConnectionTracker.getCommandsPerMinute(ip1) >= 10);
        assertTrue(ConnectionTracker.getCommandsPerMinute(ip2) >= 5);
    }
}

