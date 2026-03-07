package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for queue control operations against the hard-refactored queue API.
 * <p>These tests use a singleton queue instance, so they must run serially to avoid interference.
 */
@Execution(ExecutionMode.SAME_THREAD)
class QueueControlsTest {

    private static PersistentQueue<RelaySession> queue;

    @BeforeAll
    static void before() {
        queue = PersistentQueue.getInstance();
    }

    @AfterAll
    static void after() {
        queue.clear();
        queue.close();
    }

    @BeforeEach
    void setUp() {
        queue.clear();
    }

    @Test
    void testDeleteByUID() {
        RelaySession first = enqueueSession("1");
        RelaySession second = enqueueSession("2");
        RelaySession third = enqueueSession("3");

        assertEquals(3, queue.size());
        assertTrue(queue.deleteByUID(second.getUID()));
        assertEquals(2, queue.size());
        assertEquals(Set.of("1", "3"), activeSessionUids());
        assertFalse(queue.deleteByUID(second.getUID()));
        assertNotNull(first);
        assertNotNull(third);
    }

    @Test
    void testDeleteByUIDMissingItemReturnsFalse() {
        enqueueSession("1");

        assertFalse(queue.deleteByUID("missing"));
        assertEquals(1, queue.size());
    }

    @Test
    void testDeleteByUIDs() {
        RelaySession first = enqueueSession("1");
        RelaySession second = enqueueSession("2");
        RelaySession third = enqueueSession("3");
        RelaySession fourth = enqueueSession("4");
        RelaySession fifth = enqueueSession("5");

        int removed = queue.deleteByUIDs(List.of(second.getUID(), fourth.getUID()));

        assertEquals(2, removed);
        assertEquals(3, queue.size());
        assertEquals(Set.of("1", "3", "5"), activeSessionUids());
        assertNotNull(first);
        assertNotNull(third);
        assertNotNull(fifth);
    }

    @Test
    void testDeleteByUIDsIgnoresMissingItems() {
        RelaySession first = enqueueSession("1");
        RelaySession second = enqueueSession("2");
        RelaySession third = enqueueSession("3");

        int removed = queue.deleteByUIDs(List.of(second.getUID(), "missing"));

        assertEquals(1, removed);
        assertEquals(2, queue.size());
        assertEquals(Set.of("1", "3"), activeSessionUids());
        assertNotNull(first);
        assertNotNull(third);
    }

    @Test
    void testClear() {
        for (int i = 1; i <= 10; i++) {
            enqueueSession(String.valueOf(i));
        }

        queue.clear();

        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testRetryWorkflow() {
        RelaySession relaySession = enqueueSession("retry");

        assertEquals(0, relaySession.getRetryCount());
        assertTrue(queue.retryNow(relaySession.getUID()));

        QueueItem<RelaySession> item = queue.getByUID(relaySession.getUID());
        assertNotNull(item);
        assertEquals(QueueItemState.READY, item.getState());
        assertEquals(1, item.getRetryCount());
        assertEquals(1, item.getPayload().getRetryCount());
    }

    @Test
    void testBulkDeleteLeavesExpectedItems() {
        RelaySession[] sessions = new RelaySession[10];
        for (int i = 0; i < sessions.length; i++) {
            sessions[i] = enqueueSession("item-" + i);
        }

        int removed = queue.deleteByUIDs(List.of(
                sessions[0].getUID(),
                sessions[2].getUID(),
                sessions[4].getUID(),
                sessions[6].getUID(),
                sessions[8].getUID()
        ));

        assertEquals(5, removed);
        assertEquals(5, queue.size());
        assertEquals(Set.of("item-1", "item-3", "item-5", "item-7", "item-9"), activeSessionUids());
    }

    @Test
    void testDeleteLastItem() {
        RelaySession only = enqueueSession("only");

        assertTrue(queue.deleteByUID(only.getUID()));
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void testDeleteFromEmptyQueue() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.deleteByUID("missing"));
        assertEquals(0, queue.deleteByUIDs(List.of("a", "b", "c")));
    }

    private RelaySession enqueueSession(String sessionUid) {
        RelaySession relaySession = new RelaySession(new Session().setUID(sessionUid));
        queue.enqueue(relaySession);
        return relaySession;
    }

    private Set<String> activeSessionUids() {
        return queue.list(0, 100, QueueListFilter.activeOnly()).items().stream()
                .map(item -> item.getPayload().getSession().getUID())
                .collect(Collectors.toSet());
    }
}
