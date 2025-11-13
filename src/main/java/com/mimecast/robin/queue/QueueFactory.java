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
 *   <li>MapDB - if queueMapDB configuration exists</li>
 *   <li>MariaDB - if queueMariaDB configuration exists</li>
 *   <li>PostgreSQL - if queuePgSQL configuration exists</li>
 *   <li>MapDB - fallback default using the provided file</li>
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
     * @param file The file to use for MapDB backend (if selected)
     * @param <T>  Type of items stored in the queue
     * @return Initialized QueueDatabase instance
     */
    public static <T extends java.io.Serializable> QueueDatabase<T> createQueueDatabase(File file) {
        BasicConfig queueConfig = Config.getServer().getQueue();
        QueueDatabase<T> database;

        // Check for MapDB configuration first
        if (queueConfig.getMap().containsKey("queueMapDB")) {
            log.info("Using MapDB queue backend");
            database = new MapDBQueueDatabase<>(file);
        }
        // Check for MariaDB configuration
        else if (queueConfig.getMap().containsKey("queueMariaDB")) {
            log.info("Using MariaDB queue backend");
            database = new QueueMariaDB<>();
        }
        // Check for PostgreSQL configuration
        else if (queueConfig.getMap().containsKey("queuePgSQL")) {
            log.info("Using PostgreSQL queue backend");
            database = new QueuePgSQL<>();
        }
        // Default to MapDB
        else {
            log.info("No queue backend configured, using default MapDB");
            database = new MapDBQueueDatabase<>(file);
        }

        database.initialize();
        return database;
    }
}
