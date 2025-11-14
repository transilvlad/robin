package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the queue database factory system.
 * <p>Tests use in-memory database (all backends disabled in test resources config).
 */
class QueueDatabaseFactoryTest {

    private PersistentQueue<RelaySession> queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            try {
                queue.close();
            } catch (Exception e) {
                // Ignore errors during test cleanup.
            }
        }
        // Reset factory to default.
        Factories.setQueueDatabase(null);
    }

    @Test
    void testDefaultInMemoryImplementation() {
        // With test config, all backends are disabled, so should use in-memory
        queue = PersistentQueue.getInstance();

        assertNotNull(queue);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void testInMemoryImplementation() {
        // Explicitly set in-memory implementation
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();

        // Test basic operations
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());

        // Create a test RelaySession
        RelaySession testSession = new RelaySession(null);

        // Test enqueue
        queue.enqueue(testSession);
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());

        // Test peek
        RelaySession peeked = queue.peek();
        assertNotNull(peeked);
        assertEquals(1, queue.size()); // Size should not change after peek

        // Test dequeue
        RelaySession dequeued = queue.dequeue();
        assertNotNull(dequeued);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void testFactoryResetBehavior() {
        // First, set a custom factory
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();
        RelaySession testSession = new RelaySession(null);
        queue.enqueue(testSession);
        assertEquals(1, queue.size());
        queue.close();

        // Reset factory to null (should use default from config - in-memory for tests)
        Factories.setQueueDatabase(null);

        // Create new queue instance
        queue = PersistentQueue.getInstance();

        // The queue should be empty since it's a new instance
        assertNotNull(queue);
        assertTrue(queue.isEmpty());
    }

    @Test
    void testSnapshotFunctionality() {
        // Use in-memory (default for tests)
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();

        // Add multiple items
        RelaySession session1 = new RelaySession(null);
        RelaySession session2 = new RelaySession(null);

        queue.enqueue(session1);
        queue.enqueue(session2);

        // Test snapshot
        var snapshot = queue.snapshot();
        assertEquals(2, snapshot.size());

        // Verify snapshot doesn't modify original queue
        assertEquals(2, queue.size());
        assertFalse(queue.isEmpty());
    }
}
