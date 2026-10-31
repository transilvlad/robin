package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledLmtpDeliveryTest {

    @Test
    void deliverReturnsFalseWhenPoolIsUnavailable() {
        Session session = sessionWithEnvelope("missing-pool");

        boolean delivered = new TestPooledLmtpDelivery().withPool(null).deliver(session, 1, 0);

        assertFalse(delivered);
        assertEquals(0, session.getSessionTransactionList().getEnvelopes().size());
    }

    @Test
    void deliverAttemptCopiesTransactionsBackToSourceSession() throws Exception {
        Session sourceSession = sessionWithEnvelope("attempt-copy");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        LmtpConnectionPool.PooledLmtpConnection pooled = delivery.newPooledConnection();

        PooledLmtpDelivery.DeliveryAttemptResult delivered = delivery.deliverAttempt(sourceSession, pooled);

        assertTrue(delivered.success());
        assertEquals(1, sourceSession.getSessionTransactionList().getEnvelopes().size());
        assertTrue(sourceSession.getSessionTransactionList().getEnvelopes().getFirst().getErrors().isEmpty());
    }

    @Test
    void deliverRetriesTransactionFailuresWithoutInvalidatingHealthyConnections() {
        Session sourceSession = sessionWithEnvelope("retry");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.failAttempts = 1;

        boolean delivered = delivery.deliver(sourceSession, 2, 0);

        assertTrue(delivered);
        assertEquals(2, delivery.borrowCount);
        assertEquals(0, delivery.invalidateCount);
        assertEquals(2, delivery.returnCount);
    }

    @Test
    void deliverInvalidatesConnectionFailures() {
        Session sourceSession = sessionWithEnvelope("connection-failure");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.throwAttempts = 1;

        boolean delivered = delivery.deliver(sourceSession, 2, 0);

        assertTrue(delivered);
        assertEquals(2, delivery.borrowCount);
        assertEquals(1, delivery.invalidateCount);
        assertEquals(1, delivery.returnCount);
    }

    @Test
    void deliverInvalidatesConnectionsClosedByTheServer() {
        Session sourceSession = sessionWithEnvelope("closed-by-server");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.closedAttempts = 1;

        boolean delivered = delivery.deliver(sourceSession, 2, 0);

        assertTrue(delivered);
        assertEquals(2, delivery.borrowCount);
        assertEquals(1, delivery.invalidateCount);
        assertEquals(1, delivery.returnCount);
    }

    @Test
    void partialFinalDeliveryDoesNotRetryAlreadyDeliveredRecipientsOrReuseTruncatedConnection() {
        Session source = sessionWithEnvelope("partial-closed");
        source.getEnvelopes().getFirst().setRcpts(List.of("pepper@example.com", "unknown@example.com"));
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.partialClosedAttempts = 1;
        assertFalse(delivery.deliver(source, 3, 0));
        assertEquals(1, delivery.borrowCount);
        assertEquals(1, delivery.invalidateCount);
        assertEquals(List.of("unknown@example.com"),
                source.getSessionTransactionList().getEnvelopes().getFirst().getFailedRecipients());
    }

    @Test
    void partialIoExceptionRetainsFinalRecipientStateAndDoesNotRetryTheEnvelope() {
        Session source = sessionWithEnvelope("partial-exception");
        source.getEnvelopes().getFirst().setRcpts(List.of("pepper@example.com", "unknown@example.com"));
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.partialThrowAttempts = 1;
        assertFalse(delivery.deliver(source, 3, 0));
        assertEquals(1, delivery.borrowCount);
        assertEquals(1, delivery.invalidateCount);
        assertEquals(0, delivery.returnCount);
        assertEquals(2, source.getSessionTransactionList().getEnvelopes().getFirst().getLmtp().size());
        assertEquals(List.of("unknown@example.com"),
                source.getSessionTransactionList().getEnvelopes().getFirst().getUndeliveredLmtpRecipients(
                        source.getEnvelopes().getFirst().getRcpts()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingFinalTransactionIsUnknownNotSuccessfulDelivery(boolean noTransactions) throws Exception {
        Session source = sessionWithEnvelope("missing-final");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.noTransactionAttempts = noTransactions ? 1 : 0;
        delivery.missingFinalAttempts = noTransactions ? 0 : 1;
        var result = delivery.deliverAttempt(source, delivery.newPooledConnection());
        assertFalse(result.success());
        assertFalse(result.reusable());
        assertTrue(source.getSessionTransactionList().getEnvelopes().getFirst().getData().isError());
        assertEquals("", source.getSessionTransactionList().getEnvelopes().getFirst().getData().getResponse());
    }

    @Test
    void mixedBdatFinalRepliesAreFailuresWithoutRetryingTheDeliveredRecipient() {
        Session source = sessionWithEnvelope("partial-bdat");
        source.getEnvelopes().getFirst().setRcpts(List.of("pepper@example.com", "unknown@example.com"));
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.partialBdatAttempts = 1;
        assertFalse(delivery.deliver(source, 3, 0));
        assertEquals(1, delivery.borrowCount);
        assertEquals(1, delivery.returnCount);
        assertEquals(0, delivery.invalidateCount);
        assertEquals(List.of("unknown@example.com"),
                source.getSessionTransactionList().getEnvelopes().getFirst().getFailedRecipients());
    }

    @Test
    void acknowledgedNonfinalBdatChunkDoesNotConfirmMailboxDelivery() throws Exception {
        Session source = sessionWithEnvelope("missing-bdat-last");
        TestPooledLmtpDelivery delivery = new TestPooledLmtpDelivery();
        delivery.missingBdatLastAttempts = 1;
        var result = delivery.deliverAttempt(source, delivery.newPooledConnection());
        assertFalse(result.success());
        assertFalse(result.reusable());
        var tx = source.getSessionTransactionList().getEnvelopes().getFirst();
        assertEquals(List.of("pepper@example.com"), tx.getUndeliveredLmtpRecipients(source.getEnvelopes().getFirst().getRcpts()));
    }

    private static Session sessionWithEnvelope(String uid) {
        Session session = new Session().setUID(uid);
        session.addEnvelope(new MessageEnvelope()
                .setMail("tony@example.com")
                .setRcpts(List.of("pepper@example.com"))
                .setMessage("hello"));
        return session;
    }

    private static class TestPooledLmtpDelivery extends PooledLmtpDelivery {
        private LmtpConnectionPool pool = new LmtpConnectionPool(1, 1, 60, 60, 100, List.of("127.0.0.1"), 24, false);
        private int borrowCount;
        private int returnCount;
        private int invalidateCount;
        private int failAttempts;
        private int throwAttempts;
        private int closedAttempts;
        private int partialClosedAttempts;
        private int partialThrowAttempts;
        private int partialBdatAttempts;
        private int missingFinalAttempts;
        private int noTransactionAttempts;
        private int missingBdatLastAttempts;

        private TestPooledLmtpDelivery withPool(LmtpConnectionPool pool) {
            this.pool = pool;
            return this;
        }

        @Override
        protected LmtpConnectionPool getPool() {
            return pool;
        }

        @Override
        protected LmtpConnectionPool.PooledLmtpConnection borrowConnection(LmtpConnectionPool pool, MessageEnvelope envelope) {
            borrowCount++;
            return newPooledConnection();
        }

        @Override
        protected void returnConnection(LmtpConnectionPool pool, LmtpConnectionPool.PooledLmtpConnection pooled) {
            returnCount++;
        }

        @Override
        protected void invalidateConnection(LmtpConnectionPool pool, LmtpConnectionPool.PooledLmtpConnection pooled) {
            invalidateCount++;
        }

        @Override
        protected void processEnvelope(Connection connection) throws IOException {
            if (throwAttempts > 0) {
                throwAttempts--;
                throw new IOException("broken pipe");
            }
            if (noTransactionAttempts > 0) {
                noTransactionAttempts--;
                return;
            }
            EnvelopeTransactionList envelopeTransactionList = new EnvelopeTransactionList();
            if (partialThrowAttempts > 0) {
                partialThrowAttempts--;
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<pepper@example.com>", "250 Delivered");
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<unknown@example.com>", "", true);
                envelopeTransactionList.addTransaction("DATA", "DATA", "250 Delivered", true);
                connection.getSession().getSessionTransactionList().addEnvelope(envelopeTransactionList);
                throw new IOException("Synthetic final-reply interruption");
            } else if (partialBdatAttempts > 0) {
                partialBdatAttempts--;
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<pepper@example.com>", "250 Delivered");
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<unknown@example.com>", "550 Missing", true);
                envelopeTransactionList.addTransaction("BDAT", "BDAT 1 LAST", "250 Delivered\r\n550 Missing", true);
            } else if (missingBdatLastAttempts > 0) {
                missingBdatLastAttempts--;
                envelopeTransactionList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "250 Admitted");
                envelopeTransactionList.addTransaction("BDAT", "BDAT 1", "250 Chunk received");
            } else if (missingFinalAttempts > 0) {
                missingFinalAttempts--;
                envelopeTransactionList.addTransaction("RCPT", "RCPT TO:<pepper@example.com>", "250 Admitted");
            } else if (closedAttempts > 0) {
                closedAttempts--;
                envelopeTransactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "", true);
            } else if (partialClosedAttempts > 0) {
                partialClosedAttempts--;
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<pepper@example.com>", "250 Delivered");
                envelopeTransactionList.addTransaction("LMTP", "RCPT TO:<unknown@example.com>", "", true);
                envelopeTransactionList.addTransaction("DATA", "DATA", "250 Delivered", true);
            } else if (failAttempts > 0) {
                failAttempts--;
                envelopeTransactionList.addTransaction("DATA", "DATA", "451 temporary failure", true);
            } else {
                envelopeTransactionList.addTransaction("DATA", "DATA", "250 ok", false);
            }
            connection.getSession().getSessionTransactionList().addEnvelope(envelopeTransactionList);
        }

        private LmtpConnectionPool.PooledLmtpConnection newPooledConnection() {
            return new LmtpConnectionPool.PooledLmtpConnection(new Connection(new Session()), "test");
        }
    }
}
