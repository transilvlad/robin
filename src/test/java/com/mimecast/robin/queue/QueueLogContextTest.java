package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Queue work runs on reused threads: each item's log lines must carry that item's session and
 * sender, not whatever the thread's previous task left behind.
 */
class QueueLogContextTest {

    @AfterEach
    void tearDown() {
        Session.unpinLogContext();
        ThreadContext.clearMap();
    }

    @Test
    void labelsTheThreadWithTheItemAndClearsItAfterwards() {
        ThreadContext.put("aCode", "previous-session");
        ThreadContext.put("bCode", "/203.0.113.9:1234");
        ThreadContext.put("cCode", "previous@example.net");
        Session session = new Session().setUID("queued-session");
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        session.addEnvelope(envelope);
        QueueItem<RelaySession> item = QueueItem.ready(new RelaySession(session));

        try (var context = QueueLogContext.enter(item)) {
            assertEquals("queued-session", ThreadContext.get("aCode"));
            assertEquals("", ThreadContext.get("bCode"));
            assertEquals("tony@example.com", ThreadContext.get("cCode"));

            new Session(); // A notification session made while the item is processed.
            assertEquals("queued-session", ThreadContext.get("aCode"), "Helper sessions do not relabel the item");
        }

        assertEquals("", ThreadContext.get("aCode"));
        assertEquals("", ThreadContext.get("bCode"));
        assertEquals("", ThreadContext.get("cCode"));
        Session next = new Session();
        assertEquals(next.getUID(), ThreadContext.get("aCode"), "The pin is released for the next task");
    }

    @Test
    void anItemWithoutASessionIsLabelledByItsQueueUid() {
        ThreadContext.put("cCode", "previous@example.net");
        QueueItem<RelaySession> item = QueueItem.ready(new RelaySession(null));

        try (var context = QueueLogContext.enter(item)) {
            assertEquals(item.getUid(), ThreadContext.get("aCode"));
            assertEquals("", ThreadContext.get("cCode"));
        }
    }
}
