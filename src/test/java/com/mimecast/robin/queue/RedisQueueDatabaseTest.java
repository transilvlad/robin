package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for RedisQueueDatabase.
 * <p>These tests require a Redis instance running on localhost:6379.
 * <p>Tests are only run if REDIS_TEST_ENABLED environment variable is set.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_ENABLED", matches = "true")
class RedisQueueDatabaseTest {

    private RedisQueueDatabase<RelaySession> database;

    @BeforeEach
    void setUp() {
        // Create database instance with test configuration
        database = new RedisQueueDatabase<>();
        try {
            database.initialize();
            database.clear(); // Clean slate for each test
        } catch (Exception e) {
            // Skip tests if Redis is not available
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Redis not available: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            try {
                database.clear();
                database.close();
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }
    }

    @Test
    void testEnqueueDequeue() {
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);

        // Test enqueue
        database.enqueue(session1);
        database.enqueue(session2);
        assertEquals(2, database.size());
        assertFalse(database.isEmpty());

        // Test dequeue (FIFO order)
        RelaySession dequeued1 = database.dequeue();
        assertNotNull(dequeued1);
        assertEquals(1, database.size());

        RelaySession dequeued2 = database.dequeue();
        assertNotNull(dequeued2);
        assertEquals(0, database.size());
        assertTrue(database.isEmpty());

        // Dequeue from empty queue
        RelaySession dequeued3 = database.dequeue();
        assertNull(dequeued3);
    }

    @Test
    void testPeek() {
        assertTrue(database.isEmpty());

        // Peek on empty queue
        RelaySession peeked = database.peek();
        assertNull(peeked);

        // Add items
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        database.enqueue(session1);
        database.enqueue(session2);

        // Peek should return first item without removing
        RelaySession peeked1 = database.peek();
        assertNotNull(peeked1);
        assertEquals(2, database.size()); // Size unchanged

        RelaySession peeked2 = database.peek();
        assertNotNull(peeked2);
        assertEquals(2, database.size()); // Size still unchanged
    }

    @Test
    void testSize() {
        assertEquals(0, database.size());
        assertTrue(database.isEmpty());

        database.enqueue(new RelaySession(null));
        assertEquals(1, database.size());
        assertFalse(database.isEmpty());

        database.enqueue(new RelaySession(null));
        assertEquals(2, database.size());

        database.dequeue();
        assertEquals(1, database.size());

        database.dequeue();
        assertEquals(0, database.size());
        assertTrue(database.isEmpty());
    }

    @Test
    void testSnapshot() {
        // Empty snapshot
        List<RelaySession> snapshot = database.snapshot();
        assertNotNull(snapshot);
        assertEquals(0, snapshot.size());

        // Add items
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        RelaySession session3 = new RelaySession(null);
        database.enqueue(session1);
        database.enqueue(session2);
        database.enqueue(session3);

        // Test snapshot
        snapshot = database.snapshot();
        assertEquals(3, snapshot.size());

        // Verify queue is unchanged
        assertEquals(3, database.size());
        assertFalse(database.isEmpty());
    }

    @Test
    void testRemoveByIndex() {
        // Test invalid index
        assertFalse(database.removeByIndex(-1));
        assertFalse(database.removeByIndex(0));

        // Add items
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        RelaySession session3 = new RelaySession(null);
        database.enqueue(session1);
        database.enqueue(session2);
        database.enqueue(session3);
        assertEquals(3, database.size());

        // Remove middle item (index 1)
        assertTrue(database.removeByIndex(1));
        assertEquals(2, database.size());

        // Remove first item (index 0)
        assertTrue(database.removeByIndex(0));
        assertEquals(1, database.size());

        // Remove last remaining item
        assertTrue(database.removeByIndex(0));
        assertEquals(0, database.size());
        assertTrue(database.isEmpty());

        // Test out of bounds
        assertFalse(database.removeByIndex(0));
    }

    @Test
    void testRemoveByIndices() {
        // Test null and empty list
        assertEquals(0, database.removeByIndices(null));
        assertEquals(0, database.removeByIndices(Arrays.asList()));

        // Add items
        for (int i = 0; i < 5; i++) {
            database.enqueue(new RelaySession(null));
        }
        assertEquals(5, database.size());

        // Remove multiple items by indices
        int removed = database.removeByIndices(Arrays.asList(1, 3));
        assertEquals(2, removed);
        assertEquals(3, database.size());

        // Test with invalid indices
        removed = database.removeByIndices(Arrays.asList(-1, 10, 1));
        assertEquals(1, removed); // Only index 1 is valid
        assertEquals(2, database.size());
    }

    @Test
    void testRemoveByUID() {
        // Test with null UID
        assertFalse(database.removeByUID(null));

        // Add items with unique UIDs
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        RelaySession session3 = new RelaySession(null);
        
        database.enqueue(session1);
        database.enqueue(session2);
        database.enqueue(session3);
        assertEquals(3, database.size());

        // Remove by UID
        String uid2 = session2.getUID();
        assertTrue(database.removeByUID(uid2));
        assertEquals(2, database.size());

        // Try to remove same UID again
        assertFalse(database.removeByUID(uid2));
        assertEquals(2, database.size());

        // Remove by non-existent UID
        assertFalse(database.removeByUID("non-existent-uid"));
        assertEquals(2, database.size());
    }

    @Test
    void testRemoveByUIDs() {
        // Test with null and empty list
        assertEquals(0, database.removeByUIDs(null));
        assertEquals(0, database.removeByUIDs(Arrays.asList()));

        // Add items
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        RelaySession session3 = new RelaySession(null);
        RelaySession session4 = new RelaySession(null);
        
        database.enqueue(session1);
        database.enqueue(session2);
        database.enqueue(session3);
        database.enqueue(session4);
        assertEquals(4, database.size());

        // Remove by UIDs
        List<String> uids = Arrays.asList(session1.getUID(), session3.getUID());
        int removed = database.removeByUIDs(uids);
        assertEquals(2, removed);
        assertEquals(2, database.size());

        // Try to remove already removed UIDs
        removed = database.removeByUIDs(uids);
        assertEquals(0, removed);
        assertEquals(2, database.size());
    }

    @Test
    void testClear() {
        // Clear empty queue
        database.clear();
        assertEquals(0, database.size());

        // Add items
        database.enqueue(new RelaySession(null));
        database.enqueue(new RelaySession(null));
        database.enqueue(new RelaySession(null));
        assertEquals(3, database.size());

        // Clear queue
        database.clear();
        assertEquals(0, database.size());
        assertTrue(database.isEmpty());
    }

    @Test
    void testFIFOOrder() {
        // Create sessions with identifiable properties
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);
        RelaySession session3 = new RelaySession(null);

        // Enqueue in order
        database.enqueue(session1);
        database.enqueue(session2);
        database.enqueue(session3);

        // Dequeue should maintain FIFO order
        RelaySession dequeued1 = database.dequeue();
        assertEquals(session1.getUID(), dequeued1.getUID());

        RelaySession dequeued2 = database.dequeue();
        assertEquals(session2.getUID(), dequeued2.getUID());

        RelaySession dequeued3 = database.dequeue();
        assertEquals(session3.getUID(), dequeued3.getUID());

        assertTrue(database.isEmpty());
    }

    @Test
    void testLargeQueue() {
        // Test with a larger number of items
        int itemCount = 100;
        for (int i = 0; i < itemCount; i++) {
            database.enqueue(new RelaySession(null));
        }
        assertEquals(itemCount, database.size());

        // Test snapshot with large queue
        List<RelaySession> snapshot = database.snapshot();
        assertEquals(itemCount, snapshot.size());

        // Dequeue all items
        for (int i = 0; i < itemCount; i++) {
            assertNotNull(database.dequeue());
        }
        assertTrue(database.isEmpty());
    }
}
