package com.mimecast.robin.queue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Tag("suite")
class SqlQueueStartupIntegrationTest {
    @Test
    void incompatibleQueueIsPreservedAndStartupFails() throws Exception {
        String jdbcUrl = System.getProperty("robin.integration.jdbcUrl");
        assertNotNull(jdbcUrl, "Supply the isolated integration database URL");
        String table = "queue_startup_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(jdbcUrl, "robin", "robin");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id BIGSERIAL PRIMARY KEY, data BYTEA NOT NULL)");
            statement.execute("INSERT INTO " + table + " (data) VALUES (decode('010203','hex'))");
            TestQueue queue = new TestQueue(jdbcUrl, table);
            try {
                IllegalStateException failure = assertThrows(IllegalStateException.class, queue::initialize);
                assertTrue(failure.getCause().getMessage().contains("explicitly migrate"));
                try (var result = statement.executeQuery("SELECT encode(data,'hex') FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals("010203", result.getString(1));
                    assertFalse(result.next());
                }
                try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_name LIKE '" + table + "_legacy_%'")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            } finally {
                queue.close();
                statement.execute("DROP TABLE " + table);
            }
        }
    }

    @Test
    void claimByUidTakesOnlyReadyRows() {
        String jdbcUrl = System.getProperty("robin.integration.jdbcUrl");
        assertNotNull(jdbcUrl, "Supply the isolated integration database URL");
        String table = "queue_claim_test_" + UUID.randomUUID().toString().replace("-", "");
        PgQueue queue = new PgQueue(jdbcUrl, table);
        queue.initialize();
        try {
            queue.enqueue(QueueItem.restore("busy", 100, "busy payload").readyAt(1));
            assertEquals(1, queue.claimReady(1, 2, "worker", 1000).size());
            queue.enqueue(QueueItem.restore("future", 101, "future payload").readyAt(5000));
            queue.enqueue(QueueItem.restore("dead", 102, "dead payload").readyAt(1));
            queue.markDead("dead", "permanent failure");

            assertNull(queue.claimByUID("busy", "admin", 3000));
            assertEquals("worker", queue.getByUID("busy").getClaimOwner());
            assertNull(queue.claimByUID("dead", "admin", 3000));
            assertNull(queue.claimByUID("missing", "admin", 3000));

            QueueItem<String> claimed = queue.claimByUID("future", "admin", 3000);
            assertNotNull(claimed);
            assertEquals("future payload", claimed.getPayload());
            assertEquals(QueueItemState.CLAIMED, claimed.getState());
            assertEquals("admin", claimed.getClaimOwner());
            assertNull(queue.claimByUID("future", "admin", 3000));
            assertTrue(queue.claimReady(10, 10_000, "worker", 20_000).isEmpty());
        } finally {
            queue.clear();
            try (var connection = DriverManager.getConnection(jdbcUrl, "robin", "robin");
                 var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            } catch (Exception ignored) {
            }
            queue.close();
        }
    }

    private static class PgQueue extends SQLQueueDatabase<String> {
        PgQueue(String jdbcUrl, String table) {
            super(new DBConfig(jdbcUrl, "robin", "robin", table, 1));
        }

        @Override
        protected String getDatabaseType() { return "PostgreSQL"; }

        @Override
        protected String getCreateTableSQL() {
            return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "id BIGSERIAL PRIMARY KEY, queue_uid VARCHAR(191) NOT NULL UNIQUE, state VARCHAR(16) NOT NULL, "
                    + "next_attempt_at BIGINT NOT NULL, claimed_until BIGINT NOT NULL DEFAULT 0, claim_owner VARCHAR(255), "
                    + "created_epoch BIGINT NOT NULL, updated_epoch BIGINT NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, "
                    + "protocol VARCHAR(64), session_uid VARCHAR(255), last_error TEXT, data BYTEA NOT NULL)";
        }
    }

    private static class TestQueue extends SQLQueueDatabase<String> {
        TestQueue(String jdbcUrl, String table) {
            super(new DBConfig(jdbcUrl, "robin", "robin", table, 1));
        }

        @Override
        protected String getDatabaseType() { return "PostgreSQL"; }

        @Override
        protected String getCreateTableSQL() {
            return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "id BIGSERIAL PRIMARY KEY, queue_uid TEXT, state TEXT, next_attempt_at BIGINT, "
                    + "claimed_until BIGINT, claim_owner TEXT, created_epoch BIGINT, updated_epoch BIGINT, "
                    + "retry_count INTEGER, protocol TEXT, session_uid TEXT, last_error TEXT, data BYTEA)";
        }
    }
}
