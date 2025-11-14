package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Factory for creating QueueDatabase instances based on configuration.
 * <p>Selects the appropriate queue backend in priority order:
 * <ol>
 *   <li>If file parameter is provided (non-null), use MapDB with that file (for tests)</li>
 *   <li>MapDB - if queueMapDB.enabled is true</li>
 *   <li>MariaDB - if queueMariaDB.enabled is true</li>
 *   <li>PostgreSQL - if queuePgSQL.enabled is true</li>
 *   <li>MapDB - fallback default using queueFile from config</li>
 * </ol>
 */
public class QueueFactory {

    private static final Logger log = LogManager.getLogger(QueueFactory.class);

    /**
     * Private constructor to prevent instantiation.
     */
    private QueueFactory() {
        throw new IllegalStateException("Factory class");
    }

    /**
     * Creates and initializes a QueueDatabase instance based on configuration.
     *
     * @param file The file to use for MapDB backend. If non-null, MapDB will be used with this file (for tests).
     *             If null, backend selection is based on configuration.
     * @param <T>  Type of items stored in the queue
     * @return Initialized QueueDatabase instance
     */
    public static <T extends java.io.Serializable> QueueDatabase<T> createQueueDatabase(File file) {
        BasicConfig queueConfig = Config.getServer().getQueue();
        QueueDatabase<T> database;

        // If file is explicitly provided (not null), use MapDB with that file (for tests)
        if (file != null) {
            log.info("Using MapDB queue backend with provided file: {}", file.getAbsolutePath());
            database = new MapDBQueueDatabase<>(file);
        }
        // Check for MapDB configuration and enabled flag
        else if (queueConfig.getMap().containsKey("queueMapDB")) {
            BasicConfig mapDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMapDB"));
            if (mapDBConfig.getBooleanProperty("enabled", true)) {
                String queueFile = mapDBConfig.getStringProperty("queueFile", "/usr/local/robin/relayQueue.db");
                log.info("Using MapDB queue backend with config file: {}", queueFile);
                database = new MapDBQueueDatabase<>(new File(queueFile));
            } else {
                database = selectFromDatabaseBackends(queueConfig);
            }
        }
        // Check for MariaDB configuration and enabled flag
        else if (queueConfig.getMap().containsKey("queueMariaDB")) {
            BasicConfig mariaDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMariaDB"));
            if (mariaDBConfig.getBooleanProperty("enabled", false)) {
                log.info("Using MariaDB queue backend");
                database = new QueueMariaDB<>();
            } else {
                database = selectFromDatabaseBackends(queueConfig);
            }
        }
        // Check for PostgreSQL configuration and enabled flag
        else if (queueConfig.getMap().containsKey("queuePgSQL")) {
            BasicConfig pgSQLConfig = new BasicConfig(queueConfig.getMapProperty("queuePgSQL"));
            if (pgSQLConfig.getBooleanProperty("enabled", false)) {
                log.info("Using PostgreSQL queue backend");
                database = new QueuePgSQL<>();
            } else {
                database = selectFromDatabaseBackends(queueConfig);
            }
        }
        // Default to MapDB using queueFile from config
        else {
            String queueFile = queueConfig.getStringProperty("queueFile", "/usr/local/robin/relayQueue.db");
            log.info("No queue backend configured, using default MapDB with file: {}", queueFile);
            database = new MapDBQueueDatabase<>(new File(queueFile));
        }

        database.initialize();
        return database;
    }

    /**
     * Helper method to select from configured database backends based on enabled flag.
     */
    private static <T extends java.io.Serializable> QueueDatabase<T> selectFromDatabaseBackends(BasicConfig queueConfig) {
        // Check MariaDB
        if (queueConfig.getMap().containsKey("queueMariaDB")) {
            BasicConfig mariaDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMariaDB"));
            if (mariaDBConfig.getBooleanProperty("enabled", false)) {
                log.info("Using MariaDB queue backend");
                return new QueueMariaDB<>();
            }
        }

        // Check PostgreSQL
        if (queueConfig.getMap().containsKey("queuePgSQL")) {
            BasicConfig pgSQLConfig = new BasicConfig(queueConfig.getMapProperty("queuePgSQL"));
            if (pgSQLConfig.getBooleanProperty("enabled", false)) {
                log.info("Using PostgreSQL queue backend");
                return new QueuePgSQL<>();
            }
        }

        // Fall back to MapDB using queueFile from config
        String queueFile = queueConfig.getStringProperty("queueFile", "/usr/local/robin/relayQueue.db");
        log.info("No enabled database backend, falling back to default MapDB with file: {}", queueFile);
        return new MapDBQueueDatabase<>(new File(queueFile));
    }
}

