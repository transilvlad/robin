package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.Serializable;

/**
 * MariaDB implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by MariaDB using LONGBLOB for serialized storage.
 * <p>Configuration is loaded from {@code Config.getServer().getQueue().getMapProperty("queueMariaDB")}.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class QueueMariaDB<T extends Serializable> extends AbstractSQLQueueDatabase<T> {

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
            mariaDBConfig.getStringProperty("tableName", "queue")
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
                "data LONGBLOB NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB";
    }
}
