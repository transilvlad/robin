package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LmtpConnectionPoolTest {

    @Test
    void returnConnectionKeepsConnectionIdleWhileUnderMessageLimit() {
        TestLmtpConnectionPool pool = new TestLmtpConnectionPool(3);
        MessageEnvelope envelope = envelope();

        LmtpConnectionPool.PooledLmtpConnection first = pool.borrow(envelope);
        assertNotNull(first);
        first.recordDeliveredMessage();
        first.recordDeliveredMessage();
        pool.returnConnection(first);

        assertEquals(1, pool.getIdleCount());
        assertEquals(1, pool.getTotalConnections());
        assertEquals(0, pool.getMessageLimitRetirementCount());
        assertEquals(0, pool.closeCount);

        LmtpConnectionPool.PooledLmtpConnection reused = pool.borrow(envelope);
        assertNotNull(reused);
        assertEquals(0, pool.getIdleCount());
        assertEquals(1, pool.getBorrowedCount());
    }

    @Test
    void returnConnectionRetiresConnectionAtMessageLimit() {
        TestLmtpConnectionPool pool = new TestLmtpConnectionPool(2);
        LmtpConnectionPool.PooledLmtpConnection pooled = pool.borrow(envelope());

        assertNotNull(pooled);
        pooled.recordDeliveredMessage();
        pooled.recordDeliveredMessage();
        pool.returnConnection(pooled);

        assertEquals(0, pool.getIdleCount());
        assertEquals(0, pool.getTotalConnections());
        assertEquals(1, pool.getMessageLimitRetirementCount());
        assertEquals(1, pool.closeCount);
    }

    private static MessageEnvelope envelope() {
        return new MessageEnvelope()
                .setMail("tony@example.com")
                .setRcpts(List.of("pepper@example.com"))
                .setMessage("hello");
    }

    private static final class TestLmtpConnectionPool extends LmtpConnectionPool {
        private int closeCount;

        private TestLmtpConnectionPool(int maxMessagesPerConnection) {
            super(4, 1, 60, 60, maxMessagesPerConnection, List.of("127.0.0.1"), 24, false);
        }

        @Override
        protected PooledLmtpConnection createNewConnection(MessageEnvelope envelope) {
            Connection connection = new Connection(new Session());
            connection.getSession().addEnvelope(envelope);
            return trackNewConnection(connection, "test");
        }


        @Override
        protected boolean validateConnection(PooledLmtpConnection pooled) {
            return pooled.isValid() && !pooled.hasReachedMessageLimit(getMaxMessagesPerConnection());
        }
        @Override
        protected boolean resetConnection(PooledLmtpConnection pooled) {
            return true;
        }

        @Override
        protected void closeConnection(PooledLmtpConnection pooled) {
            closeCount++;
            super.closeConnection(pooled);
        }
    }
}
