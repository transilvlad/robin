package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.Serializable;

/**
 * MariaDB implementation of QueueDatabase.
 * <p>A persistent scheduled work queue backed by MariaDB using LONGBLOB for serialized storage.
 * <p>Configuration is loaded from {@code Config.getServer().getQueue().getMapProperty("queueMariaDB")}.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class QueueMariaDB<T extends Serializable> extends SQLQueueDatabase<T> {

    /**
     * Constructs a new QueueMariaDB instance.
     * <p>Loads configuration including jdbcUrl, username, password, and tableName from queue config.
     */
    public QueueMariaDB() {
        super(getConfig());
    }

    /**
     * Loads MariaDB configuration from application config.
     *
     * @return DBConfig with connection details
     */
    private static DBConfig getConfig() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig mariaDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMariaDB"));
        
        return new DBConfig(
            mariaDBConfig.getStringProperty("jdbcUrl", "jdbc:mariadb://localhost:3306/robin"),
            mariaDBConfig.getStringProperty("username", "robin"),
            mariaDBConfig.getStringProperty("password", ""),
            mariaDBConfig.getStringProperty("tableName", "queue"),
            Math.toIntExact(mariaDBConfig.getLongProperty("maxPoolSize", 16L))
        );
    }

    @Override
    protected String getDatabaseType() {
        return "MariaDB";
    }

    @Override
    protected String getCreateTableSQL() {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "queue_uid VARCHAR(191) NOT NULL UNIQUE, " +
                "state VARCHAR(16) NOT NULL, " +
                "next_attempt_at BIGINT NOT NULL, " +
                "claimed_until BIGINT NOT NULL DEFAULT 0, " +
                "claim_owner VARCHAR(255) NULL, " +
                "created_epoch BIGINT NOT NULL, " +
                "updated_epoch BIGINT NOT NULL, " +
                "retry_count INT NOT NULL DEFAULT 0, " +
                "protocol VARCHAR(64) NULL, " +
                "session_uid VARCHAR(255) NULL, " +
                "last_error TEXT NULL, " +
                "data LONGBLOB NOT NULL" +
                ") ENGINE=InnoDB";
    }
}
