package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent FIFO queue that delegates to a QueueDatabase implementation.
 * <p>Uses the Factory pattern to allow different database backends.
 */
public class PersistentQueue<T extends Serializable> implements Closeable {

    private static final Logger log = LogManager.getLogger(PersistentQueue.class);

    private final File file;
    private final QueueDatabase<T> database;

    // Singleton instances map.
    private static final Map<String, PersistentQueue<RelaySession>> instances = new HashMap<>();

    /**
     * Get a singleton instance of PersistentQueue for the given file and queue name.
     *
     * @param file The file to store the database.
     * @return The PersistentQueue instance.
     */
    @SuppressWarnings("unchecked")
    public static PersistentQueue<RelaySession> getInstance(File file) {
        String instanceKey = file.getAbsolutePath();

        if (!instances.containsKey(instanceKey)) {
            instances.put(instanceKey, new PersistentQueue<>(file));
        }

        return instances.get(instanceKey);
    }

    /**
     * Constructs a new PersistentQueue instance.
     *
     * @param file The file to store the database.
     */
    @SuppressWarnings("unchecked")
    PersistentQueue(File file) {
        this.file = file;
        this.database = (QueueDatabase<T>) Factories.getQueueDatabase(file);
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @return Self.
     */
    public PersistentQueue<T> enqueue(T item) {
        database.enqueue(item);
        return this;
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     */
    public T dequeue() {
        return database.dequeue();
    }

    /**
     * Peek at the head without removing.
     */
    public T peek() {
        return database.peek();
    }

    /**
     * Check if the queue is empty.
     */
    public boolean isEmpty() {
        return database.isEmpty();
    }

    /**
     * Get the size of the queue.
     */
    public long size() {
        return database.size();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection (e.g., metrics/health).
     */
    public List<T> snapshot() {
        return database.snapshot();
    }

    /**
     * Close the database.
     */
    @Override
    public void close() {
        instances.remove(file.getAbsolutePath());
        try {
            database.close();
        } catch (Exception e) {
            // Log the error but don't propagate it to maintain close() contract.
            // This is especially important for MapDB WAL files on Windows.
            log.error("Error closing queue database for file {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }
}
