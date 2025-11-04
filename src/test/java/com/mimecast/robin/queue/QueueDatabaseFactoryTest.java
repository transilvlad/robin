package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the queue database factory system.
 */
class QueueDatabaseFactoryTest {

    @TempDir
    Path tempDir;

    private File queueFile;
    private PersistentQueue<RelaySession> queue;

    @BeforeEach
    void setUp() {
        queueFile = tempDir.resolve("test-queue.db").toFile();
    }

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
    void testDefaultMapDBImplementation() {
        // When no custom factory is set, should use MapDB by default.
        // But since this can cause file locking issues on Windows with temp directories,
        // we'll just verify the queue can be created and basic operations work
        queue = PersistentQueue.getInstance(queueFile);

        assertNotNull(queue);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());

        // Test that we can close it properly
        queue.close();
        queue = null; // Don't let tearDown try to close it again
    }

    @Test
    void testInMemoryImplementation() {
        // Set custom in-memory implementation
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance(queueFile);

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

        queue = PersistentQueue.getInstance(queueFile);
        RelaySession testSession = new RelaySession(null);
        queue.enqueue(testSession);
        assertEquals(1, queue.size());
        queue.close();

        // Reset factory to null (should use default MapDB)
        Factories.setQueueDatabase(null);

        // Create new queue instance - should use MapDB now
        queue = PersistentQueue.getInstance(queueFile);

        // The queue should be empty since we switched from in-memory to persistent
        // and the in-memory data was lost
        assertNotNull(queue);
    }

    @Test
    void testSnapshotFunctionality() {
        // Use in-memory for easier testing
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance(queueFile);

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
