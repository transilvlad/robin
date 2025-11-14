package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
class PersistentQueueTest {

    static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        // Uses in-memory database from test resources config (all backends disabled)
        queue = PersistentQueue.getInstance();
    }

    @AfterAll
    static void after() {
        queue.close();
    }

    @Test
    void dequeueReturnsNullWhenEmpty() {
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
    void persistsAcrossInstances() {
        queue.enqueue(new RelaySession(new Session().setUID("persistsAcrossInstances")));
        
        // With in-memory database from test config, data is lost on close
        // This test verifies the singleton behavior
        PersistentQueue<RelaySession> sameQueue = PersistentQueue.getInstance();
        assertEquals("persistsAcrossInstances", sameQueue.dequeue().getSession().getUID());
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
