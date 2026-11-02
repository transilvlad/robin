package com.mimecast.robin.queue;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class RelayRecipientFailureTest {
    private PersistentQueue<RelaySession> queue;
    private final List<Path> notifications = new ArrayList<>();
    private static final byte[] CONTENT = "From: author@example.com\r\n\r\noriginal retry content\r\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void init() throws Exception {
        ConfigLoader.load("src/test/resources/cfg/");
        queue = PersistentQueue.getInstance();
        queue.clear();
    }

    @AfterEach
    void clean() throws Exception {
        for (Path path : notifications) Files.deleteIfExists(path);
        queue.clear();
        queue.close();
        ConfigLoader.load("src/test/resources/cfg/");
    }

    private MessageEnvelope envelope(String sender, String... recipients) {
        return new MessageEnvelope().setMail(sender).setRcpts(new ArrayList<>(List.of(recipients))).setBytes(CONTENT);
    }

    private RelaySession relay(MessageEnvelope... envelopes) {
        var session = new Session();
        for (var envelope : envelopes) session.addEnvelope(envelope);
        return new RelaySession(session);
    }

    private EnvelopeTransactionList rcpts(String... responses) {
        var transactions = new EnvelopeTransactionList();
        for (int i = 0; i < responses.length; i += 2) {
            transactions.addTransaction("RCPT", "RCPT TO:<" + responses[i] + ">", responses[i + 1],
                    !responses[i + 1].startsWith("2"));
        }
        return transactions;
    }

    private RelayQueueWorkResult process(RelaySession relay, Consumer<RelaySession> script) {
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 100).getFirst();
        var worker = new RelayDequeue(queue) {
            @Override void attemptDelivery(RelaySession item) { script.accept(item); }
        };
        var result = worker.processClaimedItem(claimed, now);
        for (var notification : result.newItems()) {
            for (var envelope : notification.getSession().getEnvelopes()) {
                if (envelope.getFile() != null) notifications.add(Path.of(envelope.getFile()));
            }
        }
        return result;
    }

    private String report(RelaySession bounce) throws Exception {
        return new String(bounce.getSession().getEnvelopes().getFirst().readMessageBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void firstNullSenderDoesNotSuppressOtherEnvelopeNotificationForSameTarget() throws Exception {
        var relay = relay(envelope("", "same@destination.test"), envelope("second@example.com", "same@destination.test"));
        var result = process(relay, item -> {
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@destination.test", "550 5.1.1 First"));
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@destination.test", "552 5.2.2 Second"));
        });
        assertEquals(1, result.newItems().size());
        var bounce = result.newItems().getFirst();
        assertEquals(List.of("second@example.com"), bounce.getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(report(bounce).contains("Status: 5.2.2"));
        assertEquals("", bounce.getSession().getEnvelopes().getFirst().getMail());
    }

    @Test
    void repeatedTargetAcrossDistinctSendersGetsDistinctEnvelopeDiagnosticsAndNotifications() throws Exception {
        var relay = relay(envelope("first@example.com", "same@destination.test", "same@destination.test"),
                envelope("second@example.com", "same@destination.test"));
        var result = process(relay, item -> {
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@destination.test", "550 5.1.1 First"));
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@destination.test", "552 5.2.2 Second"));
        });
        assertEquals(2, result.newItems().size(), "Only duplicate targets inside the same envelope are deduplicated");
        assertEquals(List.of("first@example.com"), result.newItems().getFirst().getSession().getEnvelopes().getFirst().getRcpts());
        assertEquals(List.of("second@example.com"), result.newItems().getLast().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(report(result.newItems().getFirst()).contains("Status: 5.1.1"));
        assertTrue(report(result.newItems().getLast()).contains("Status: 5.2.2"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedSmtpPartitionsDeliveredPermanentAndTemporaryRecipientsInOneDurableMutation(boolean bdat) throws Exception {
        var relay = relay(envelope("author@example.com", "soft@example.net", "hard@example.net", "ok@example.net"));
        var result = process(relay, item -> {
            var tx = rcpts("soft@example.net", "451 4.2.0 Try later", "hard@example.net", "550 5.1.1 Missing",
                    "ok@example.net", "250 OK");
            if (bdat) tx.addTransaction("BDAT", "BDAT 1 LAST", "250 Delivered");
            else tx.addTransaction("DATA", "250 Delivered");
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(List.of("soft@example.net"), result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertArrayEquals(CONTENT, result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().readMessageBytes());
        assertEquals(1, result.newItems().size());
        assertTrue(Files.exists(notifications.getFirst()));
        assertTrue(report(result.newItems().getFirst()).contains("Final-Recipient: RFC822; hard@example.net"));
        assertTrue(result.mutation().lastError().startsWith("451"), "Historical hard failure must not replace pending soft diagnostic");
        assertEquals(3, queue.getByUID(relay.getUID()).getPayload().getSession().getEnvelopes().getFirst().getRcpts().size(),
                "Claimed payload changes are not committed before the notification enqueue batch");
        queue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));
        assertEquals(2, queue.size());
        assertEquals(List.of("soft@example.net"), queue.getByUID(relay.getUID()).getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        long nextTime = result.mutation().nextAttemptAtEpochSeconds();
        var next = queue.claimReady(10, nextTime, "next", nextTime + 100)
                .stream().filter(item -> item.getUid().equals(relay.getUID())).findFirst().orElseThrow();
        var worker = new RelayDequeue(queue) {
            @Override void attemptDelivery(RelaySession item) {
                item.getSession().getSessionTransactionList().addEnvelope(rcpts("soft@example.net", "451 Later"));
            }
        };
        var retry = worker.processClaimedItem(next, nextTime);
        assertTrue(retry.newItems().isEmpty(), "The hard recipient must not generate another DSN on a later retry");
        assertArrayEquals(CONTENT, retry.mutation().item().getPayload().getSession().getEnvelopes().getFirst().readMessageBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownOrTemporaryDataKeepsAcceptedRecipientRetryContentWhileExplicitHardRcptIsNotRetried(boolean temporary) throws Exception {
        var relay = relay(envelope("author@example.com", "ok@example.net", "hard@example.net"));
        var result = process(relay, item -> {
            var tx = rcpts("ok@example.net", "250 OK", "hard@example.net", "550 5.1.1 Missing");
            if (temporary) tx.addTransaction("DATA", "451 4.3.0 Body deferred", true);
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        var pending = result.mutation().item().getPayload().getSession().getEnvelopes().getFirst();
        assertEquals(List.of("ok@example.net"), pending.getRcpts());
        assertArrayEquals(CONTENT, pending.readMessageBytes());
        assertTrue(result.mutation().lastError().startsWith(temporary ? "451 4.3.0" : "451 4.4.2"));
        assertEquals(1, result.newItems().size());
    }

    @Test
    void permanentDataRejectsOnlyPreviouslyAdmittedRecipientsNotTemporaryRcptFailures() {
        var relay = relay(envelope("author@example.com", "soft@example.net", "ok@example.net"));
        var result = process(relay, item -> {
            var tx = rcpts("soft@example.net", "451 Try later", "ok@example.net", "250 OK");
            tx.addTransaction("DATA", "550 5.7.1 Content prohibited", true);
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        assertEquals(List.of("soft@example.net"), result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertEquals(1, result.newItems().size());
    }

    private RelayDequeue failingNotifications(Consumer<RelaySession> script) {
        return new RelayDequeue(queue) {
            @Override void attemptDelivery(RelaySession item) { script.accept(item); }
            @Override RelaySession createBounceSession(RelaySession item, MessageEnvelope envelope, String recipient) {
                throw new IllegalStateException("Synthetic notification persistence failure");
            }
        };
    }

    @Test
    void notificationPersistenceFailureKeepsPermanentRecipientQueuedInsteadOfThrowing() {
        var relay = relay(envelope("author@example.com", "hard@example.net", "soft@example.net"));
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 100).getFirst();
        var worker = failingNotifications(item -> item.getSession().getSessionTransactionList().addEnvelope(
                rcpts("hard@example.net", "550 Missing", "soft@example.net", "451 Try later")));
        var result = worker.processClaimedItem(claimed, now);
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(List.of("hard@example.net", "soft@example.net"),
                result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(result.newItems().isEmpty());
    }

    @Test
    void notificationPersistenceFailureAfterCommittedDeliveryDoesNotRedeliverAcceptedRecipient() {
        var relay = relay(envelope("author@example.com", "ok@example.net", "hard@example.net"));
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 100).getFirst();
        var worker = failingNotifications(item -> {
            var tx = rcpts("ok@example.net", "250 OK", "hard@example.net", "550 5.1.1 Missing");
            tx.addTransaction("DATA", "250 Delivered");
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        var result = worker.processClaimedItem(claimed, now);
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(List.of("hard@example.net"),
                result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts(),
                "The accepted recipient is retired so a later attempt cannot deliver it twice");
        assertArrayEquals(CONTENT, readFirst(result));
        queue.applyMutations(new QueueMutationBatch<>(List.of(result.mutation()), result.newItems()));

        long next = result.mutation().nextAttemptAtEpochSeconds();
        var reclaimed = queue.claimReady(1, next, "next", next + 100).getFirst();
        var attempted = new ArrayList<String>();
        var retry = new RelayDequeue(queue) {
            @Override void attemptDelivery(RelaySession item) {
                attempted.addAll(item.getSession().getEnvelopes().getFirst().getRcpts());
                item.getSession().getSessionTransactionList().addEnvelope(rcpts("hard@example.net", "550 5.1.1 Missing"));
            }
        }.processClaimedItem(reclaimed, next);
        assertEquals(List.of("hard@example.net"), attempted);
        assertEquals(QueueMutationType.DEAD, retry.mutation().type());
        assertEquals(1, retry.newItems().size(), "The owed notification is prepared once persistence recovers");
        notifications.add(Path.of(retry.newItems().getFirst().getSession().getEnvelopes().getFirst().getFile()));
    }

    private static byte[] readFirst(RelayQueueWorkResult result) {
        try {
            return result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().readMessageBytes();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secondNotificationFailureEnqueuesFirstAndKeepsOnlyOwedRecipientQueued(boolean whollyPermanent) {
        var recipients = whollyPermanent ? new String[]{"first@example.net", "second@example.net"}
                : new String[]{"first@example.net", "second@example.net", "soft@example.net"};
        var relay = relay(envelope("author@example.com", recipients));
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 100).getFirst();
        var worker = new RelayDequeue(queue) {
            private int count;
            @Override void attemptDelivery(RelaySession item) {
                var tx = rcpts("first@example.net", "550 First", "second@example.net", "550 Second");
                if (!whollyPermanent) tx.addTransaction("RCPT", "RCPT TO:<soft@example.net>", "451 Later", true);
                item.getSession().getSessionTransactionList().addEnvelope(tx);
            }
            @Override RelaySession createBounceSession(RelaySession item, MessageEnvelope envelope, String recipient) {
                if (++count == 2) throw new IllegalStateException("Synthetic second notification failure");
                return super.createBounceSession(item, envelope, recipient);
            }
        };
        var result = worker.processClaimedItem(claimed, now);
        assertEquals(1, result.newItems().size());
        var notification = Path.of(result.newItems().getFirst().getSession().getEnvelopes().getFirst().getFile());
        notifications.add(notification);
        assertTrue(Files.exists(notification));
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(whollyPermanent ? List.of("second@example.net") : List.of("second@example.net", "soft@example.net"),
                result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
    }

    @Test
    void expiredItemWithFailedNotificationIsHeldUntilTheNotificationIsPrepared() {
        var relay = relay(envelope("author@example.com", "late@example.net"));
        queue.enqueue(relay);
        long now = Instant.now().getEpochSecond();
        var claimed = queue.claimReady(1, now, "test", now + 100).getFirst();
        var held = failingNotifications(item -> fail("Expiry must not attempt delivery")).expireClaimedItem(claimed);
        assertEquals(QueueMutationType.RESCHEDULE, held.mutation().type());
        assertEquals(List.of("late@example.net"),
                held.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());

        var expired = new RelayDequeue(queue).expireClaimedItem(held.mutation().item());
        assertEquals(QueueMutationType.DEAD, expired.mutation().type());
        assertEquals(1, expired.newItems().size());
        notifications.add(Path.of(expired.newItems().getFirst().getSession().getEnvelopes().getFirst().getFile()));
    }

    @Test
    void unroutableReversePathRetiresPermanentFailureWithoutNotification() {
        var result = process(relay(envelope("postmaster", "hard@example.net")), item ->
                item.getSession().getSessionTransactionList().addEnvelope(rcpts("hard@example.net", "550 Missing")));
        assertEquals(QueueMutationType.DEAD, result.mutation().type());
        assertTrue(result.newItems().isEmpty());
    }

    @Test
    void nullSenderMixedFailuresSuppressNotificationButKeepTemporaryContent() throws Exception {
        var result = process(relay(envelope("", "hard@example.net", "soft@example.net")), item ->
                item.getSession().getSessionTransactionList().addEnvelope(
                        rcpts("hard@example.net", "550 Missing", "soft@example.net", "451 Later")));
        assertTrue(result.newItems().isEmpty());
        assertEquals(List.of("soft@example.net"), result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertArrayEquals(CONTENT, result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().readMessageBytes());
    }

    @Test
    void missingLmtpFinalRecipientDoesNotInheritAnotherRecipientsPermanentReply() {
        var relay = relay(envelope("author@example.com", "hard@example.net", "unknown@example.net"));
        relay.setProtocol("lmtp");
        var result = process(relay, item -> {
            var tx = new EnvelopeTransactionList();
            tx.addTransaction("LMTP", "RCPT TO:<hard@example.net>", "550 Missing", true);
            tx.addTransaction("DATA", "550 Missing", true);
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(List.of("unknown@example.net"), result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(result.mutation().lastError().startsWith("451 4.4.2"));
        assertEquals(1, result.newItems().size());
    }

    @Test
    void removingEarlierCompletedEnvelopesRetainsRemainingEnvelopeTransactionAlignment() throws Exception {
        var relay = relay(envelope("delivered@example.com", "same@example.net"), envelope("failed@example.com", "same@example.net"),
                envelope("pending@example.com", "same@example.net"));
        var result = process(relay, item -> {
            var delivered = rcpts("same@example.net", "250 OK");
            delivered.addTransaction("DATA", "250 Delivered");
            item.getSession().getSessionTransactionList().addEnvelope(delivered);
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@example.net", "550 5.1.1 Missing"));
            item.getSession().getSessionTransactionList().addEnvelope(rcpts("same@example.net", "451 4.2.0 Pending"));
        });
        var pending = result.mutation().item().getPayload();
        assertEquals(1, pending.getSession().getEnvelopes().size());
        assertEquals("pending@example.com", pending.getSession().getEnvelopes().getFirst().getMail());
        assertEquals(1, pending.getSession().getSessionTransactionList().getEnvelopes().size());
        assertTrue(pending.getRejection(pending.getSession().getEnvelopes().getFirst(), "same@example.net").startsWith("451 4.2.0"));
        assertEquals(List.of("failed@example.com"), result.newItems().getFirst().getSession().getEnvelopes().getFirst().getRcpts());
        assertTrue(report(result.newItems().getFirst()).contains("Status: 5.1.1"));
    }

    @Test
    void lmtpFinalPerRecipientRepliesDoNotUseSmtpAggregateDataAcceptance() throws Exception {
        var relay = relay(envelope("author@example.com", "ok@example.net", "hard@example.net", "soft@example.net"));
        relay.setProtocol("lmtp");
        var result = process(relay, item -> {
            var tx = rcpts("ok@example.net", "250 Admitted", "hard@example.net", "250 Admitted", "soft@example.net", "250 Admitted");
            tx.addTransaction("LMTP", "RCPT TO:<ok@example.net>", "250 Delivered");
            tx.addTransaction("LMTP", "RCPT TO:<hard@example.net>", "550 5.1.1 Missing", true);
            tx.addTransaction("LMTP", "RCPT TO:<soft@example.net>", "451 4.2.0 Later", true);
            tx.addTransaction("DATA", "250 Delivered\r\n550 5.1.1 Missing\r\n451 4.2.0 Later", true);
            item.getSession().getSessionTransactionList().addEnvelope(tx);
        });
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(List.of("soft@example.net"), result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().getRcpts());
        assertArrayEquals(CONTENT, result.mutation().item().getPayload().getSession().getEnvelopes().getFirst().readMessageBytes());
        assertTrue(result.mutation().lastError().startsWith("451 4.2.0"));
        assertTrue(report(result.newItems().getFirst()).contains("Status: 5.1.1"));
    }
}
