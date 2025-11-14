package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.Serializable;
import java.util.List;

/**
 * A persistent FIFO queue that delegates to a QueueDatabase implementation.
 * <p>Uses the Factory pattern to allow different database backends (MapDB, MariaDB, PostgreSQL, or InMemory).
 * Backend selection is configuration-driven via {@link QueueFactory}.
 * <p>This class implements the Singleton pattern. All code should use {@link #getInstance()} to obtain
 * the queue instance, which will be backed by the configured database implementation.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class PersistentQueue<T extends Serializable> implements Closeable {

    private static final Logger log = LogManager.getLogger(PersistentQueue.class);

    private final QueueDatabase<T> database;

    // Singleton instance.
    private static PersistentQueue<RelaySession> instance;

    /**
     * Get the singleton instance of PersistentQueue using configuration-based backend selection.
     * <p>The backend is selected by {@link QueueFactory} based on configuration in queue.json5.
     * Backend priority: MapDB → MariaDB → PostgreSQL → InMemory.
     *
     * @return The PersistentQueue instance
     */
    @SuppressWarnings("unchecked")
    public static synchronized PersistentQueue<RelaySession> getInstance() {
        if (instance == null) {
            instance = new PersistentQueue<>();
        }
        return instance;
    }

    /**
     * Constructs a new PersistentQueue instance.
     * <p>Private constructor to enforce singleton pattern.
     * Uses {@link Factories#getQueueDatabase()} which delegates to {@link QueueFactory}.
     */
    @SuppressWarnings("unchecked")
    private PersistentQueue() {
        this.database = (QueueDatabase<T>) Factories.getQueueDatabase();
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @param item Item to enqueue
     * @return Self for method chaining
     */
    public PersistentQueue<T> enqueue(T item) {
        database.enqueue(item);
        return this;
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     *
     * @return The head item or null if empty
     */
    public T dequeue() {
        return database.dequeue();
    }

    /**
     * Peek at the head without removing.
     *
     * @return The head item or null if empty
     */
    public T peek() {
        return database.peek();
    }

    /**
     * Check if the queue is empty.
     *
     * @return true if the queue is empty
     */
    public boolean isEmpty() {
        return database.isEmpty();
    }

    /**
     * Get the size of the queue.
     *
     * @return The number of items in the queue
     */
    public long size() {
        return database.size();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection (e.g., service/health).
     *
     * @return Immutable list of all items currently in the queue
     */
    public List<T> snapshot() {
        return database.snapshot();
    }

    /**
     * Remove an item from the queue by index (0-based).
     *
     * @param index The index of the item to remove
     * @return true if item was removed, false if index was out of bounds
     */
    public boolean removeByIndex(int index) {
        return database.removeByIndex(index);
    }

    /**
     * Remove items from the queue by indices (0-based).
     *
     * @param indices The indices of items to remove
     * @return Number of items successfully removed
     */
    public int removeByIndices(List<Integer> indices) {
        return database.removeByIndices(indices);
    }

    /**
     * Remove an item from the queue by UID (for RelaySession).
     *
     * @param uid The UID of the item to remove
     * @return true if item was removed, false if not found
     */
    public boolean removeByUID(String uid) {
        return database.removeByUID(uid);
    }

    /**
     * Remove items from the queue by UIDs (for RelaySession).
     *
     * @param uids The UIDs of items to remove
     * @return Number of items successfully removed
     */
    public int removeByUIDs(List<String> uids) {
        return database.removeByUIDs(uids);
    }

    /**
     * Clear all items from the queue.
     */
    public void clear() {
        database.clear();
    }

    /**
     * Close the database and reset the singleton instance.
     * <p>After calling close(), the next call to {@link #getInstance()} will create a new instance.
     * <p>This method is synchronized to prevent race conditions with {@link #getInstance()}.
     */
    @Override
    public synchronized void close() {
        try {
            database.close();
        } catch (Exception e) {
            // Log the error but don't propagate it to maintain close() contract.
            log.error("Error closing queue database: {}", e.getMessage());
        } finally {
            // Set instance to null after closing to prevent race conditions
            instance = null;
        }
    }
}
