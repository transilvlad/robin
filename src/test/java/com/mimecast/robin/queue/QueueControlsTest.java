package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for queue control operations (delete, retry, bounce).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class QueueControlsTest {

    static File dbFile;
    static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        dbFile = Paths.get(System.getProperty("java.io.tmpdir"), "test-" + UUID.randomUUID() + ".db").toFile();
        dbFile.deleteOnExit();
        queue = PersistentQueue.getInstance(dbFile);
    }

    @AfterAll
    static void after() {
        queue.close();
    }

    @BeforeEach
    void setUp() {
        // Clear queue before each test.
        queue.clear();
    }

    @Test
    void testRemoveByIndex() {
        // Enqueue 3 items.
        queue.enqueue(new RelaySession(new Session().setUID("1")));
        queue.enqueue(new RelaySession(new Session().setUID("2")));
        queue.enqueue(new RelaySession(new Session().setUID("3")));

        assertEquals(3, queue.size());

        // Remove middle item (index 1).
        assertTrue(queue.removeByIndex(1));
        assertEquals(2, queue.size());

        // Verify remaining items.
        List<RelaySession> items = queue.snapshot();
        assertEquals("1", items.get(0).getSession().getUID());
        assertEquals("3", items.get(1).getSession().getUID());
    }

    @Test
    void testRemoveByIndexOutOfBounds() {
        queue.enqueue(new RelaySession(new Session().setUID("1")));

        // Try to remove index out of bounds.
        assertFalse(queue.removeByIndex(5));
        assertFalse(queue.removeByIndex(-1));

        // Queue should be unchanged.
        assertEquals(1, queue.size());
    }

    @Test
    void testRemoveByIndices() {
        // Enqueue 5 items.
        for (int i = 1; i <= 5; i++) {
            queue.enqueue(new RelaySession(new Session().setUID(String.valueOf(i))));
        }

        assertEquals(5, queue.size());

        // Remove indices 1, 3 (UIDs 2 and 4).
        int removed = queue.removeByIndices(Arrays.asList(1, 3));
        assertEquals(2, removed);
        assertEquals(3, queue.size());

        // Verify remaining items.
        List<RelaySession> items = queue.snapshot();
        assertEquals("1", items.get(0).getSession().getUID());
        assertEquals("3", items.get(1).getSession().getUID());
        assertEquals("5", items.get(2).getSession().getUID());
    }

    @Test
    void testRemoveByIndicesSomeOutOfBounds() {
        // Enqueue 3 items.
        queue.enqueue(new RelaySession(new Session().setUID("1")));
        queue.enqueue(new RelaySession(new Session().setUID("2")));
        queue.enqueue(new RelaySession(new Session().setUID("3")));

        // Remove indices 1 (valid) and 10 (invalid).
        int removed = queue.removeByIndices(Arrays.asList(1, 10));
        assertEquals(1, removed);
        assertEquals(2, queue.size());

        // Verify remaining items.
        List<RelaySession> items = queue.snapshot();
        assertEquals("1", items.get(0).getSession().getUID());
        assertEquals("3", items.get(1).getSession().getUID());
    }

    @Test
    void testRemoveByIndicesEmpty() {
        queue.enqueue(new RelaySession(new Session().setUID("1")));

        // Remove empty list.
        int removed = queue.removeByIndices(Arrays.asList());
        assertEquals(0, removed);
        assertEquals(1, queue.size());
    }

    @Test
    void testClear() {
        // Enqueue multiple items.
        for (int i = 1; i <= 10; i++) {
            queue.enqueue(new RelaySession(new Session().setUID(String.valueOf(i))));
        }

        assertEquals(10, queue.size());

        // Clear the queue.
        queue.clear();
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testRetryWorkflow() {
        // Create a relay session and enqueue it.
        RelaySession relaySession = new RelaySession(new Session().setUID("test-retry"));
        assertEquals(0, relaySession.getRetryCount());

        queue.enqueue(relaySession);

        // Simulate retry: dequeue, bump retry count, re-enqueue.
        RelaySession item = queue.dequeue();
        assertNotNull(item);
        assertEquals(0, item.getRetryCount());

        item.bumpRetryCount();
        assertEquals(1, item.getRetryCount());

        queue.enqueue(item);

        // Verify it's back in the queue with incremented retry count.
        assertEquals(1, queue.size());
        RelaySession requeued = queue.peek();
        assertEquals("test-retry", requeued.getSession().getUID());
        assertEquals(1, requeued.getRetryCount());
    }

    @Test
    void testBulkOperations() {
        // Enqueue 10 items.
        for (int i = 0; i < 10; i++) {
            queue.enqueue(new RelaySession(new Session().setUID("item-" + i)));
        }

        assertEquals(10, queue.size());

        // Bulk delete indices 0, 2, 4, 6, 8 (even indices).
        int removed = queue.removeByIndices(Arrays.asList(0, 2, 4, 6, 8));
        assertEquals(5, removed);
        assertEquals(5, queue.size());

        // Verify remaining items are odd indices.
        List<RelaySession> items = queue.snapshot();
        assertEquals("item-1", items.get(0).getSession().getUID());
        assertEquals("item-3", items.get(1).getSession().getUID());
        assertEquals("item-5", items.get(2).getSession().getUID());
        assertEquals("item-7", items.get(3).getSession().getUID());
        assertEquals("item-9", items.get(4).getSession().getUID());
    }

    @Test
    void testRemoveLastItem() {
        queue.enqueue(new RelaySession(new Session().setUID("only")));

        assertTrue(queue.removeByIndex(0));
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void testRemoveFromEmptyQueue() {
        assertTrue(queue.isEmpty());

        assertFalse(queue.removeByIndex(0));
        assertEquals(0, queue.removeByIndices(Arrays.asList(0, 1, 2)));
    }
}
