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
     * Close the database.
     * For in-memory implementation, this clears the queue.
     */
    @Override
    public void close() {
        queue.clear();
        size.set(0);
    }
}
