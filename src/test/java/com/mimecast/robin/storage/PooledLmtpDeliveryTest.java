package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.Test;

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
            EnvelopeTransactionList envelopeTransactionList = new EnvelopeTransactionList();
            if (failAttempts > 0) {
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
