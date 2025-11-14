package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.Serializable;

/**
 * PostgreSQL implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by PostgreSQL using BYTEA for serialized storage.
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
            pgSQLConfig.getStringProperty("tableName", "queue")
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
                "data BYTEA NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";
    }
}
