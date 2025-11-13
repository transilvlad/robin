package com.mimecast.robin.queue;

import java.io.Closeable;
import java.io.Serializable;
import java.util.List;

/**
 * Interface for queue database implementations.
 * <p>Defines the contract for persistent queue storage backends.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public interface QueueDatabase<T extends Serializable> extends Closeable {

    /**
     * Add an item to the tail of the queue.
     *
     * @param item The item to enqueue
     */
    void enqueue(T item);

    /**
     * Remove and return the head of the queue, or null if empty.
     *
     * @return The head item or null if empty
     */
    T dequeue();

    /**
     * Peek at the head without removing.
     *
     * @return The head item or null if empty
     */
    T peek();

    /**
     * Check if the queue is empty.
     *
     * @return true if the queue is empty
     */
    boolean isEmpty();

    /**
     * Get the size of the queue.
     *
     * @return The number of items in the queue
     */
    long size();

    /**
     * Take a snapshot copy of current values for read-only inspection.
     *
     * @return List of all items in the queue
     */
    List<T> snapshot();

    /**
     * Remove an item from the queue by index (0-based).
     *
     * @param index The index of the item to remove
     * @return true if item was removed, false if index was out of bounds
     */
    boolean removeByIndex(int index);

    /**
     * Remove items from the queue by indices (0-based).
     *
     * @param indices The indices of items to remove
     * @return Number of items successfully removed
     */
    int removeByIndices(List<Integer> indices);

    /**
     * Remove an item from the queue by UID (for RelaySession).
     *
     * @param uid The UID of the item to remove
     * @return true if item was removed, false if not found
     */
    boolean removeByUID(String uid);

    /**
     * Remove items from the queue by UIDs (for RelaySession).
     *
     * @param uids The UIDs of items to remove
     * @return Number of items successfully removed
     */
    int removeByUIDs(List<String> uids);

    /**
     * Clear all items from the queue.
     */
    void clear();

    /**
     * Initialize the database connection/resources.
     * Called during queue creation.
     */
    void initialize();
}
