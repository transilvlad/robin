package com.mimecast.robin.smtp.session;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionLogContextTest {

    @AfterEach
    void tearDown() {
        Session.unpinLogContext();
        ThreadContext.remove("aCode");
    }

    @Test
    void newSessionLabelsUnpinnedThread() {
        Session first = new Session();
        assertEquals(first.getUID(), ThreadContext.get("aCode"));

        Session second = new Session();
        assertEquals(second.getUID(), ThreadContext.get("aCode"));
    }

    @Test
    void helperSessionsKeepPinnedLabel() {
        Session inbound = new Session();
        Session.pinLogContext(inbound);

        new Session();
        new Session().setUID("helper");
        assertEquals(inbound.getUID(), ThreadContext.get("aCode"));
    }

    @Test
    void unpinRestoresLabelling() {
        Session.pinLogContext(new Session());
        Session.unpinLogContext();

        Session next = new Session();
        assertEquals(next.getUID(), ThreadContext.get("aCode"));
    }

    @Test
    void pinIsPerThread() throws InterruptedException {
        Session.pinLogContext(new Session());

        String[] label = new String[2];
        Thread other = new Thread(() -> {
            Session session = new Session();
            label[0] = session.getUID();
            label[1] = ThreadContext.get("aCode");
        });
        other.start();
        other.join();

        assertEquals(label[0], label[1]);
    }
}
