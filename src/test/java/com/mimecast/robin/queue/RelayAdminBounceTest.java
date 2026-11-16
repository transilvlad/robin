package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Administrator bounces: the sender is notified, a message being delivered is left alone, and
 * a notification that cannot be prepared leaves the message queued as it was.
 */
@Isolated
class RelayAdminBounceTest {
    private static final String REASON = SmtpResponses.QUEUE_ADMIN_BOUNCE_554;

    private PersistentQueue<RelaySession> queue;
    private final List<Path> files = new ArrayList<>();

    @BeforeEach
    void setUp() {
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (QueueItem<RelaySession> item : queue.list(0, 1000, QueueListFilter.activeOnly().setStates(java.util.EnumSet.allOf(QueueItemState.class))).items()) {
            trackFiles(item.getPayload());
        }
        queue.clear();
        queue.close();
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void bounceNotifiesTheSenderOfEveryRemainingRecipientAndDeadLettersTheItem() throws Exception {
        RelaySession relay = relay("admin-bounce", "author@example.com", "one@example.net", "two@example.net");
        queue.enqueue(relay);

        assertEquals(RelayDequeue.AdminBounce.BOUNCED, new RelayDequeue(queue).bounceByAdmin(relay.getUID(), REASON));

        QueueItem<RelaySession> dead = queue.getByUID(relay.getUID());
        assertEquals(QueueItemState.DEAD, dead.getState());
        assertEquals(REASON, dead.getLastError());


        List<QueueItem<RelaySession>> notifications = queue.list(0, 10, QueueListFilter.activeOnly()).items();
        assertEquals(2, notifications.size(), "One notification per remaining recipient");
        List<String> reported = new ArrayList<>();
        for (QueueItem<RelaySession> notification : notifications) {
            MessageEnvelope envelope = notification.getPayload().getSession().getEnvelopes().getFirst();
            assertEquals("", envelope.getMail(), "Notifications have a null reverse path");
            assertEquals(List.of("author@example.com"), envelope.getRcpts());
            String report = new String(envelope.readMessageBytes(), StandardCharsets.UTF_8);
            assertTrue(report.contains("Status: 5.0.0"), report);
            assertTrue(report.contains("Diagnostic-Code: SMTP; " + REASON), report);
            reported.add(report.contains("Final-Recipient: RFC822; one@example.net") ? "one" : "two");
        }
        assertEquals(List.of("one", "two"), reported.stream().sorted().toList());
    }

    @Test
    void anItemAWorkerIsDeliveringIsLeftAlone() {
        RelaySession relay = relay("busy", "author@example.com", "one@example.net");
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        assertEquals(1, queue.claimReady(1, now, "worker", now + 300).size());

        assertEquals(RelayDequeue.AdminBounce.NOT_READY, new RelayDequeue(queue).bounceByAdmin(relay.getUID(), REASON));

        QueueItem<RelaySession> item = queue.getByUID(relay.getUID());
        assertEquals(QueueItemState.CLAIMED, item.getState());
        assertEquals("worker", item.getClaimOwner());
        assertEquals(1, queue.size(), "No notification is queued");
    }

    @Test
    void deadAndMissingItemsAreNotBouncedAgain() {
        RelaySession relay = relay("already-dead", "author@example.com", "one@example.net");
        queue.enqueue(relay);
        queue.markDead(relay.getUID(), "550 earlier failure");

        RelayDequeue dequeue = new RelayDequeue(queue);
        assertEquals(RelayDequeue.AdminBounce.NOT_READY, dequeue.bounceByAdmin(relay.getUID(), REASON));
        assertEquals(RelayDequeue.AdminBounce.NOT_READY, dequeue.bounceByAdmin("missing", REASON));
        assertEquals("550 earlier failure", queue.getByUID(relay.getUID()).getLastError());
        assertEquals(0, queue.size());
    }

    @Test
    void aNotificationThatCannotBePreparedLeavesTheItemQueuedAsItWas() {
        RelaySession relay = relay("unprepared", "author@example.com", "one@example.net", "two@example.net");
        QueueItem<RelaySession> queued = queue.enqueue(relay);
        List<RelaySession> prepared = new ArrayList<>();
        RelayDequeue dequeue = new RelayDequeue(queue) {
            @Override
            RelaySession createBounceSession(RelaySession original, MessageEnvelope envelope, String recipient) {
                if (recipient.startsWith("two")) {
                    throw new IllegalStateException("disk full");
                }
                RelaySession notification = super.createBounceSession(original, envelope, recipient);
                prepared.add(notification);
                return notification;
            }
        };

        assertEquals(RelayDequeue.AdminBounce.NOT_NOTIFIED, dequeue.bounceByAdmin(relay.getUID(), REASON));

        QueueItem<RelaySession> item = queue.getByUID(relay.getUID());
        assertEquals(QueueItemState.READY, item.getState());
        assertEquals(queued.getNextAttemptAtEpochSeconds(), item.getNextAttemptAtEpochSeconds());
        assertEquals(List.of("one@example.net", "two@example.net"),
                item.getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(item.getPayload().getSession().getSessionTransactionList().getTransactions().isEmpty(),
                "The stored payload does not carry the bounce reason");
        assertEquals(1, queue.size(), "No notification is queued");
        assertEquals(1, prepared.size());
        String discarded = prepared.getFirst().getSession().getEnvelopes().getFirst().getFile();
        assertNotNull(discarded);
        assertFalse(Files.exists(Path.of(discarded)), "The prepared notification's file is removed");
    }

    @Test
    void aNullSenderIsDeadLetteredWithoutANotification() {
        RelaySession relay = relay("null-sender", "", "one@example.net");
        queue.enqueue(relay);

        assertEquals(RelayDequeue.AdminBounce.BOUNCED, new RelayDequeue(queue).bounceByAdmin(relay.getUID(), REASON));

        assertEquals(QueueItemState.DEAD, queue.getByUID(relay.getUID()).getState());
        assertEquals(0, queue.size());
    }

    private RelaySession relay(String uid, String sender, String... recipients) {
        Session session = new Session().setUID(uid);
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail(sender);
        envelope.setRcpts(new ArrayList<>(List.of(recipients)));
        session.addEnvelope(envelope);
        return new RelaySession(session).setProtocol("mx");
    }

    private void trackFiles(RelaySession relay) {
        if (relay == null || relay.getSession() == null) return;
        for (MessageEnvelope envelope : relay.getSession().getEnvelopes()) {
            if (envelope.getFile() != null) files.add(Path.of(envelope.getFile()));
        }
    }
}
