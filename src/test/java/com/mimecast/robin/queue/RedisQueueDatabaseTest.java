package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for RedisQueueDatabase.
 * <p>These tests require a Redis instance running on localhost:6379.
 * <p>Tests are only run if REDIS_TEST_ENABLED environment variable is set.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_ENABLED", matches = "true")
class RedisQueueDatabaseTest {

    private RedisQueueDatabase<RelaySession> database;

    @BeforeEach
    void setUp() {
        database = new RedisQueueDatabase<>();
        try {
            database.initialize();
            database.clear();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Redis not available: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            try {
                database.clear();
                database.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testEnqueueClaimAndAcknowledge() {
        RelaySession session1 = relaySession("session-1");
        RelaySession session2 = relaySession("session-2");

        database.enqueue(QueueItem.ready(session1));
        database.enqueue(QueueItem.ready(session2));
        assertEquals(2, database.size());

        long now = Instant.now().getEpochSecond();
        List<QueueItem<RelaySession>> claimed = database.claimReady(10, now, "redis-test", now + 60);

        assertEquals(2, claimed.size());
        assertEquals(2, database.stats().claimedCount());
        for (QueueItem<RelaySession> item : claimed) {
            assertEquals(QueueItemState.CLAIMED, item.getState());
            assertTrue(database.acknowledge(item.getUid()));
        }

        assertEquals(0, database.size());
        assertEquals(0, database.stats().claimedCount());
    }

    @Test
    void testListReturnsActiveItems() {
        database.enqueue(QueueItem.ready(relaySession("session-1")));
        database.enqueue(QueueItem.ready(relaySession("session-2")));
        database.enqueue(QueueItem.ready(relaySession("session-3")));

        QueuePage<RelaySession> page = database.list(0, 10, QueueListFilter.activeOnly());

        assertEquals(3, page.total());
        assertEquals(3, page.items().size());
        assertEquals(Set.of("session-1", "session-2", "session-3"), page.items().stream()
                .map(item -> item.getPayload().getSession().getUID())
                .collect(Collectors.toSet()));
    }

    @Test
    void testRescheduleMakesClaimedItemReadyAgain() {
        RelaySession relaySession = relaySession("retry");
        QueueItem<RelaySession> item = database.enqueue(QueueItem.ready(relaySession));
        long now = Instant.now().getEpochSecond();
        QueueItem<RelaySession> claimed = database.claimReady(1, now, "redis-test", now + 60).getFirst();

        relaySession.bumpRetryCount();
        claimed.setPayload(relaySession).setRetryCount(relaySession.getRetryCount());
        long nextAttempt = now + 120;

        assertTrue(database.reschedule(claimed, nextAttempt, "temporary failure"));

        QueueItem<RelaySession> updated = database.getByUID(item.getUid());
        assertNotNull(updated);
        assertEquals(QueueItemState.READY, updated.getState());
        assertEquals(1, updated.getRetryCount());
        assertEquals(nextAttempt, updated.getNextAttemptAtEpochSeconds());
        assertEquals("temporary failure", updated.getLastError());
    }

    @Test
    void testReleaseExpiredClaims() {
        QueueItem<RelaySession> item = database.enqueue(QueueItem.ready(relaySession("expired")));
        long now = Instant.now().getEpochSecond();
        database.claimReady(1, now, "redis-test", now - 1);

        int released = database.releaseExpiredClaims(now);

        assertEquals(1, released);
        QueueItem<RelaySession> updated = database.getByUID(item.getUid());
        assertNotNull(updated);
        assertEquals(QueueItemState.READY, updated.getState());
        assertEquals(1, database.stats().readyCount());
        assertEquals(0, database.stats().claimedCount());
    }

    @Test
    void testDeleteByUID() {
        QueueItem<RelaySession> first = database.enqueue(QueueItem.ready(relaySession("session-1")));
        QueueItem<RelaySession> second = database.enqueue(QueueItem.ready(relaySession("session-2")));
        QueueItem<RelaySession> third = database.enqueue(QueueItem.ready(relaySession("session-3")));

        assertTrue(database.deleteByUID(second.getUid()));
        assertFalse(database.deleteByUID(second.getUid()));
        assertFalse(database.deleteByUID("missing"));
        assertEquals(2, database.size());
        assertEquals(Set.of(first.getUid(), third.getUid()), database.list(0, 10, QueueListFilter.activeOnly()).items().stream()
                .map(QueueItem::getUid)
                .collect(Collectors.toSet()));
    }

    @Test
    void testDeleteByUIDs() {
        QueueItem<RelaySession> first = database.enqueue(QueueItem.ready(relaySession("session-1")));
        QueueItem<RelaySession> second = database.enqueue(QueueItem.ready(relaySession("session-2")));
        QueueItem<RelaySession> third = database.enqueue(QueueItem.ready(relaySession("session-3")));
        QueueItem<RelaySession> fourth = database.enqueue(QueueItem.ready(relaySession("session-4")));

        int removed = database.deleteByUIDs(List.of(first.getUid(), third.getUid(), "missing"));

        assertEquals(2, removed);
        assertEquals(2, database.size());
        assertEquals(Set.of(second.getUid(), fourth.getUid()), database.list(0, 10, QueueListFilter.activeOnly()).items().stream()
                .map(QueueItem::getUid)
                .collect(Collectors.toSet()));
    }

    @Test
    void testMarkDeadMovesItemOutOfActiveQueue() {
        QueueItem<RelaySession> item = database.enqueue(QueueItem.ready(relaySession("dead")));

        assertTrue(database.markDead(item.getUid(), "permanent failure"));

        QueueItem<RelaySession> updated = database.getByUID(item.getUid());
        assertNotNull(updated);
        assertEquals(0, database.size());
        assertEquals(QueueItemState.DEAD, updated.getState());
        assertEquals(1, database.stats().deadCount());
        assertEquals(0, database.stats().readyCount());
    }

    @Test
    void testClear() {
        database.enqueue(QueueItem.ready(relaySession("session-1")));
        database.enqueue(QueueItem.ready(relaySession("session-2")));
        database.enqueue(QueueItem.ready(relaySession("session-3")));

        database.clear();

        assertEquals(0, database.size());
        assertEquals(0, database.stats().readyCount());
        assertTrue(database.list(0, 10, QueueListFilter.activeOnly()).items().isEmpty());
        assertNull(database.getByUID("missing"));
    }

    @Test
    void testLargeQueue() {
        int itemCount = 100;
        for (int i = 0; i < itemCount; i++) {
            database.enqueue(QueueItem.ready(relaySession("session-" + i)));
        }

        QueuePage<RelaySession> page = database.list(0, itemCount, QueueListFilter.activeOnly());

        assertEquals(itemCount, database.size());
        assertEquals(itemCount, page.total());
        assertEquals(itemCount, page.items().size());
    }

    private RelaySession relaySession(String sessionUid) {
        return new RelaySession(new Session().setUID(sessionUid));
    }
}
