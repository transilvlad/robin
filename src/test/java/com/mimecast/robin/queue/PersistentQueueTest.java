package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
class PersistentQueueTest {

    static File dbFile;
    static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        dbFile = Paths.get(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".db").toFile();
        dbFile.deleteOnExit();
        queue = PersistentQueue.getInstance(dbFile);
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
        queue.close();

        queue = PersistentQueue.getInstance(dbFile);
        assertEquals("persistsAcrossInstances", queue.dequeue().getSession().getUID());
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
