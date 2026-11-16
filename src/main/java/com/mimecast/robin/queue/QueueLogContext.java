package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.ThreadContext;

/**
 * Labels a pooled thread's log lines with one queue item while it is processed.
 * <p>Sets the item's session UID and sender, pinned so notification sessions do not relabel them,
 * and clears the context on close.
 */
final class QueueLogContext implements AutoCloseable {

    private QueueLogContext() {
    }

    /**
     * Labels this thread with the item's session until closed.
     *
     * @param item Queue item about to be processed; may be null or carry no session.
     * @return Context to close when the item is done.
     */
    static QueueLogContext enter(QueueItem<RelaySession> item) {
        Session.clearLogContext();
        RelaySession relay = item != null ? item.getPayload() : null;
        Session session = relay != null ? relay.getSession() : null;
        if (session != null) {
            Session.pinLogContext(session);
            ThreadContext.put("cCode", sender(session));
        } else if (item != null) {
            ThreadContext.put("aCode", item.getUid());
        }
        return new QueueLogContext();
    }

    @Override
    public void close() {
        Session.clearLogContext();
    }

    private static String sender(Session session) {
        for (MessageEnvelope envelope : session.getEnvelopes()) {
            if (envelope != null && envelope.getMail() != null) {
                return envelope.getMail();
            }
        }
        return "";
    }
}
