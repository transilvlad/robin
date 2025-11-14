package com.mimecast.robin.queue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of QueueDatabase for testing or temporary queues.
 * <p>This implementation does not persist data to disk and is lost on application restart.
 * <p>Uses {@link CopyOnWriteArrayList} for thread-safe FIFO operations with indexed access support.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class InMemoryQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private final CopyOnWriteArrayList<T> queue = new CopyOnWriteArrayList<>();

    /**
     * Initialize the database connection/resources.
     * <p>No initialization needed for in-memory implementation.
     */
    @Override
    public void initialize() {
        // No initialization needed for in-memory implementation.
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @param item The item to enqueue
     */
    @Override
    public void enqueue(T item) {
        queue.add(item);
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     *
     * @return The head item or null if empty
     */
    @Override
    public T dequeue() {
        if (queue.isEmpty()) {
            return null;
        }
        return queue.remove(0);
    }

    /**
     * Peek at the head without removing.
     *
     * @return The head item or null if empty
     */
    @Override
    public T peek() {
        if (queue.isEmpty()) {
            return null;
        }
        return queue.get(0);
    }

    /**
     * Check if the queue is empty.
     *
     * @return true if the queue is empty
     */
    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Get the size of the queue.
     *
     * @return The number of items in the queue
     */
    @Override
    public long size() {
        return queue.size();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection.
     *
     * @return Immutable list of all items in the queue
     */
    @Override
    public List<T> snapshot() {
        return new ArrayList<>(queue);
    }

    /**
     * Remove an item from the queue by index (0-based).
     *
     * @param index The index of the item to remove
     * @return true if item was removed, false if index was out of bounds
     */
    @Override
    public boolean removeByIndex(int index) {
        if (index < 0 || index >= queue.size()) {
            return false;
        }
        
        queue.remove(index);
        return true;
    }

    /**
     * Remove items from the queue by indices (0-based).
     * <p>Indices are processed in descending order to avoid index shifting issues.
     *
     * @param indices The indices of items to remove
     * @return Number of items successfully removed
     */
    @Override
    public int removeByIndices(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return 0;
        }
        
        // Sort indices in descending order to avoid index shifting issues
        List<Integer> sortedIndices = new ArrayList<>(indices);
        sortedIndices.sort((a, b) -> b.compareTo(a));
        
        int removed = 0;
        for (int index : sortedIndices) {
            if (removeByIndex(index)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Remove an item from the queue by UID (for RelaySession).
     *
     * @param uid The UID of the item to remove
     * @return true if item was removed, false if not found
     */
    @Override
    public boolean removeByUID(String uid) {
        if (uid == null) return false;
        for (int i = 0; i < queue.size(); i++) {
            T item = queue.get(i);
            if (item instanceof RelaySession relaySession) {
                if (uid.equals(relaySession.getUID())) {
                    queue.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove items from the queue by UIDs (for RelaySession).
     *
     * @param uids The UIDs of items to remove
     * @return Number of items successfully removed
     */
    @Override
    public int removeByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String uid : uids) {
            if (removeByUID(uid)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Clear all items from the queue.
     */
    @Override
    public void clear() {
        queue.clear();
    }

    /**
     * Close the database.
     * <p>For in-memory implementation, this clears the queue.
     */
    @Override
    public void close() {
        clear();
    }
}
