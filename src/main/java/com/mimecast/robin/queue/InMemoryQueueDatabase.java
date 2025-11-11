package com.mimecast.robin.queue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of QueueDatabase for testing or temporary queues.
 * <p>This implementation does not persist data to disk and is lost on application restart.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class InMemoryQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong size = new AtomicLong(0);

    /**
     * Initialize the database connection/resources.
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
        queue.offer(item);
        size.incrementAndGet();
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     */
    @Override
    public T dequeue() {
        T item = queue.poll();
        if (item != null) {
            size.decrementAndGet();
        }
        return item;
    }

    /**
     * Peek at the head without removing.
     */
    @Override
    public T peek() {
        return queue.peek();
    }

    /**
     * Check if the queue is empty.
     */
    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Get the size of the queue.
     */
    @Override
    public long size() {
        return size.get();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection.
     */
    @Override
    public List<T> snapshot() {
        return new ArrayList<>(queue);
    }

    /**
     * Remove an item from the queue by index (0-based).
     */
    @Override
    public boolean removeByIndex(int index) {
        if (index < 0) {
            return false;
        }
        List<T> items = new ArrayList<>(queue);
        if (index >= items.size()) {
            return false;
        }
        T item = items.get(index);
        boolean removed = queue.remove(item);
        if (removed) {
            size.decrementAndGet();
        }
        return removed;
    }

    /**
     * Remove items from the queue by indices (0-based).
     */
    @Override
    public int removeByIndices(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return 0;
        }
        
        // Take snapshot and sort indices in descending order to avoid index shift issues
        List<T> items = new ArrayList<>(queue);
        List<Integer> sortedIndices = new ArrayList<>(indices);
        sortedIndices.sort((a, b) -> b - a);
        
        int removed = 0;
        for (int index : sortedIndices) {
            if (index >= 0 && index < items.size()) {
                T item = items.get(index);
                if (queue.remove(item)) {
                    size.decrementAndGet();
                    removed++;
                }
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
        size.set(0);
    }

    /**
     * Close the database.
     * For in-memory implementation, this clears the queue.
     */
    @Override
    public void close() {
        clear();
    }
}
