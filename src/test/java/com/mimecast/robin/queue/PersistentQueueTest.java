package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for PersistentQueue.
 * <p>These tests use a singleton queue instance, so they must run serially to avoid interference.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PersistentQueueTest {

    private static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterAll
    static void after() {
        queue.clear();
        queue.close();
    }

    @Test
    void claimReadyReturnsEmptyWhenQueueIsEmpty() {
        queue.clear();

        assertEquals(0, queue.size());
        assertTrue(queue.claimReady(10, Instant.now().getEpochSecond(), "test", Instant.now().getEpochSecond() + 60).isEmpty());
    }

    @Test
    void enqueueClaimAndAcknowledgeWorks() {
        queue.clear();
        RelaySession first = new RelaySession(new Session().setUID("1"));
        RelaySession second = new RelaySession(new Session().setUID("2"));
        queue.enqueue(first);
        queue.enqueue(second);

        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(10, now, "test", now + 300);

        assertEquals(2, claimed.size());
        assertEquals(Set.of("1", "2"), sessionIds(claimed));

        for (QueueItem<RelaySession> item : claimed) {
            assertTrue(queue.acknowledge(item.getUid()));
        }

        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }


    @Test
    void applyMutationsMarksClaimedItemDeadWithoutLeavingClaimIndexStateBehind() {
        queue.clear();
        RelaySession session = new RelaySession(new Session().setUID("dead-batch"));
        queue.enqueue(session);

        long now = Instant.now().getEpochSecond();
        QueueItem<RelaySession> claimed = queue.claimReady(1, now, "test", now + 300).getFirst();
        claimed.setPayload(session).dead("permanent failure");

        queue.applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.dead(claimed, "permanent failure")), List.of()));

        QueueItem<RelaySession> updated = queue.getByUID(claimed.getUid());
        assertEquals(0, queue.size());
        assertEquals(1, queue.stats().deadCount());
        assertEquals(QueueItemState.DEAD, updated.getState());
    }

    @Test
    void listDoesNotRemoveItems() {
        queue.clear();
        queue.enqueue(new RelaySession(new Session().setUID("peek")));

        QueuePage<RelaySession> page = queue.list(0, 10, QueueListFilter.activeOnly());

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals("peek", page.items().getFirst().getPayload().getSession().getUID());
        assertEquals(1, queue.size());
    }

    @Test
    void multipleEnqueueClaimAndAcknowledgeRemovesAllItems() {
        queue.clear();
        for (int i = 0; i < 10; i++) {
            queue.enqueue(new RelaySession(new Session().setUID(String.valueOf(i))));
        }

        assertEquals(10, queue.size());

        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(10, now, "test", now + 300);
        assertEquals(10, claimed.size());
        assertEquals(Set.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"), sessionIds(claimed));

        for (QueueItem<RelaySession> item : claimed) {
            assertTrue(queue.acknowledge(item.getUid()));
        }

        assertTrue(queue.isEmpty());
    }

    private static Set<String> sessionIds(List<QueueItem<RelaySession>> items) {
        return items.stream()
                .map(item -> item.getPayload().getSession().getUID())
                .collect(Collectors.toSet());
    }
}
