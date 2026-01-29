package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PersistentQueue.
 * <p>These tests use a singleton queue instance, so they must run serially to avoid interference.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
class PersistentQueueTest {

    static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        // Uses in-memory database from test resources config (all backends disabled).
        queue = PersistentQueue.getInstance();

        // Clear queue in case other tests left items (singleton issue).
        while (!queue.isEmpty()) {
            queue.dequeue();
        }
    }

    @AfterAll
    static void after() {
        // Clear queue before closing.
        while (!queue.isEmpty()) {
            queue.dequeue();
        }
        queue.close();
    }

    @Test
    void dequeueReturnsNullWhenEmpty() {
        // Ensure queue is empty first.
        while (!queue.isEmpty()) {
            queue.dequeue();
        }
        assertNull(queue.dequeue());
    }

    @Test
    void enqueueAndDequeueWorks() {
        queue.enqueue(new RelaySession(new Session().setUID("1")));
        queue.enqueue(new RelaySession(new Session().setUID("2")));

        assertEquals(2, queue.size());
        assertEquals("1", queue.dequeue().getSession().getUID());
        assertEquals("2", queue.dequeue().getSession().getUID());
        assertTrue(queue.isEmpty());
        assertNull(queue.dequeue());
    }

    @Test
    void peekDoesNotRemove() {
        queue.enqueue(new RelaySession(new Session().setUID("peekDoesNotRemove")));

        assertEquals("peekDoesNotRemove", queue.peek().getSession().getUID());
        assertEquals(1, queue.size());
        // Clean up.
        assertEquals("peekDoesNotRemove", queue.dequeue().getSession().getUID());
        assertTrue(queue.isEmpty());
    }

    @Test
    void multipleEnqueueDequeue() {
        for (int i = 0; i < 10; i++) {
            queue.enqueue(new RelaySession(new Session().setUID(String.valueOf(i))));
        }
        assertEquals(10, queue.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(String.valueOf(i), queue.dequeue().getSession().getUID());
        }
        assertTrue(queue.isEmpty());
    }
}
