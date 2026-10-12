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
