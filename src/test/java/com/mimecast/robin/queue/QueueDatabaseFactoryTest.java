package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the queue database factory system.
 * <p>Tests use in-memory database when no persistent backend is configured.
 */
@Isolated
class QueueDatabaseFactoryTest {

    private PersistentQueue<RelaySession> queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            try {
                queue.clear();
                queue.close();
            } catch (Exception ignored) {
            }
        }
        Factories.setQueueDatabase(null);
    }

    @Test
    void testDefaultInMemoryImplementation() {
        queue = PersistentQueue.getInstance();
        queue.clear();

        assertNotNull(queue);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void testInMemoryImplementation() {
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();
        RelaySession testSession = new RelaySession(null);

        queue.enqueue(testSession);
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());

        QueuePage<RelaySession> page = queue.list(0, 10, QueueListFilter.activeOnly());
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());

        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 60);
        assertEquals(1, claimed.size());
        assertTrue(queue.acknowledge(claimed.getFirst().getUid()));
        assertTrue(queue.isEmpty());
    }

    @Test
    void testFactoryResetBehavior() {
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();
        queue.enqueue(new RelaySession(null));
        assertEquals(1, queue.size());
        queue.close();
        queue = null;

        Factories.setQueueDatabase(null);

        queue = PersistentQueue.getInstance();
        queue.clear();

        assertNotNull(queue);
        assertTrue(queue.isEmpty());
    }

    @Test
    void testListFunctionality() {
        Factories.setQueueDatabase(() -> {
            InMemoryQueueDatabase<RelaySession> db = new InMemoryQueueDatabase<>();
            db.initialize();
            return db;
        });

        queue = PersistentQueue.getInstance();
        queue.enqueue(new RelaySession(null));
        queue.enqueue(new RelaySession(null));

        QueuePage<RelaySession> page = queue.list(0, 10, QueueListFilter.activeOnly());

        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
        assertEquals(2, queue.size());
        assertFalse(queue.isEmpty());
    }
}
