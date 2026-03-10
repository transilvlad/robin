package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.Serializable;

/**
 * PostgreSQL implementation of QueueDatabase.
 * <p>A persistent scheduled work queue backed by PostgreSQL using BYTEA for serialized storage.
 * <p>Configuration is loaded from {@code Config.getServer().getQueue().getMapProperty("queuePgSQL")}.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class QueuePgSQL<T extends Serializable> extends SQLQueueDatabase<T> {

    /**
     * Constructs a new QueuePgSQL instance.
     * <p>Loads configuration including jdbcUrl, username, password, and tableName from queue config.
     */
    public QueuePgSQL() {
        super(getConfig());
    }

    /**
     * Loads PostgreSQL configuration from application config.
     *
     * @return DBConfig with connection details
     */
    private static DBConfig getConfig() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig pgSQLConfig = new BasicConfig(queueConfig.getMapProperty("queuePgSQL"));

        return new DBConfig(
            pgSQLConfig.getStringProperty("jdbcUrl", "jdbc:postgresql://localhost:5432/robin"),
            pgSQLConfig.getStringProperty("username", "robin"),
            pgSQLConfig.getStringProperty("password", ""),
            pgSQLConfig.getStringProperty("tableName", "queue"),
            Math.toIntExact(pgSQLConfig.getLongProperty("maxPoolSize", 16L))
        );
    }

    @Override
    protected String getDatabaseType() {
        return "PostgreSQL";
    }

    @Override
    protected String getCreateTableSQL() {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "queue_uid VARCHAR(191) NOT NULL UNIQUE, " +
                "state VARCHAR(16) NOT NULL, " +
                "next_attempt_at BIGINT NOT NULL, " +
                "claimed_until BIGINT NOT NULL DEFAULT 0, " +
                "claim_owner VARCHAR(255), " +
                "created_epoch BIGINT NOT NULL, " +
                "updated_epoch BIGINT NOT NULL, " +
                "retry_count INTEGER NOT NULL DEFAULT 0, " +
                "protocol VARCHAR(64), " +
                "session_uid VARCHAR(255), " +
                "last_error TEXT, " +
                "data BYTEA NOT NULL" +
                ")";
    }
}
