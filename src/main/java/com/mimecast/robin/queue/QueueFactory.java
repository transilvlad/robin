package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for creating QueueDatabase instances based on configuration.
 * <p>Selects the appropriate queue backend based on enabled flag in priority order:
 * <ol>
 *   <li>MapDB - if {@code queueMapDB.enabled} is true (default for production)</li>
 *   <li>MariaDB - if {@code queueMariaDB.enabled} is true</li>
 *   <li>PostgreSQL - if {@code queuePgSQL.enabled} is true</li>
 *   <li>InMemory - fallback when all backends are disabled (default for tests)</li>
 * </ol>
 * <p>All production code should use {@link PersistentQueue#getInstance()} which delegates
 * to this factory for backend selection. The InMemory backend provides no persistence and
 * is automatically used when all other backends are disabled, making it ideal for unit tests.
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
     * <p>Backend selection is determined by the enabled flags in the queue configuration:
     * <ul>
     *   <li>If {@code queueMapDB.enabled} is true, returns MapDB implementation</li>
     *   <li>Else if {@code queueMariaDB.enabled} is true, returns MariaDB implementation</li>
     *   <li>Else if {@code queuePgSQL.enabled} is true, returns PostgreSQL implementation</li>
     *   <li>Else returns InMemory implementation (no persistence)</li>
     * </ul>
     *
     * @param <T> Type of items stored in the queue
     * @return Initialized QueueDatabase instance
     */
    public static <T extends java.io.Serializable> QueueDatabase<T> createQueueDatabase() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        QueueDatabase<T> database;

        // Check for MapDB configuration and enabled flag
        if (queueConfig.getMap().containsKey("queueMapDB")) {
            BasicConfig mapDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMapDB"));
            if (mapDBConfig.getBooleanProperty("enabled", true)) {
                String queueFile = mapDBConfig.getStringProperty("queueFile", "/usr/local/robin/relayQueue.db");
                int concurrencyScale = Math.toIntExact(mapDBConfig.getLongProperty("concurrencyScale", 32L));
                log.info("Using MapDB queue backend with config file: {}", queueFile);
                database = new MapDBQueueDatabase<>(new java.io.File(queueFile), concurrencyScale);
                database.initialize();
                return database;
            }
        }

        // Check for MariaDB configuration and enabled flag
        if (queueConfig.getMap().containsKey("queueMariaDB")) {
            BasicConfig mariaDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMariaDB"));
            if (mariaDBConfig.getBooleanProperty("enabled", false)) {
                log.info("Using MariaDB queue backend");
                database = new QueueMariaDB<>();
                database.initialize();
                return database;
            }
        }

        // Check for PostgreSQL configuration and enabled flag
        if (queueConfig.getMap().containsKey("queuePgSQL")) {
            BasicConfig pgSQLConfig = new BasicConfig(queueConfig.getMapProperty("queuePgSQL"));
            if (pgSQLConfig.getBooleanProperty("enabled", false)) {
                log.info("Using PostgreSQL queue backend");
                database = new QueuePgSQL<>();
                database.initialize();
                return database;
            }
        }

        // Fall back to in-memory database when all backends are disabled (typically for tests)
        log.info("All queue backends disabled, using in-memory queue database");
        database = new InMemoryQueueDatabase<>();
        database.initialize();
        return database;
    }
}

