package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.mapdb.*;
import org.mapdb.serializer.SerializerJava;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent FIFO queue backed by MapDB v3.
 */
public class PersistentQueue<T extends Serializable> implements Closeable {

    private final File file;
    private final DB db;
    private final BTreeMap<Long, T> queue;
    private final Atomic.Long seq;

    // Singleton instances map.
    private static final Map<String, PersistentQueue<RelaySession>> instances = new HashMap<>();

    /**
     * Get a singleton instance of PersistentQueue for the given file and queue name.
     *
     * @param file The file to store the database.
     * @return The PersistentQueue instance.
     */
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
    private PersistentQueue(File file) {
        this.file = file;

        this.db = DBMaker
                .fileDB(file)
                .fileMmapEnableIfSupported()
                .concurrencyScale(Math.toIntExact(Config.getServer().getQueue().getLongProperty("concurrencyScale", 32L)))
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        this.seq = db.atomicLong("queue_seq").createOrOpen();
        this.queue = db.treeMap("queue_map", Serializer.LONG, new SerializerJava()).createOrOpen();
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @return Self.
     */
    public PersistentQueue<T> enqueue(T item) {
        long id = seq.incrementAndGet();
        queue.put(id, item);
        db.commit();
        return this;
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     */
    public T dequeue() {
        Map.Entry<Long, T> first = queue.pollFirstEntry();
        if (first == null) return null;
        db.commit();
        return first.getValue();
    }

    /**
     * Peek at the head without removing.
     */
    public T peek() {
        Map.Entry<Long, T> first = queue.firstEntry();
        return first != null ? first.getValue() : null;
    }

    /**
     * Check if the queue is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Get the size of the queue.
     */
    public long size() {
        return queue.sizeLong();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection (e.g., metrics/health).
     */
    public List<T> snapshot() {
        return new ArrayList<>(queue.values());
    }

    /**
     * Close the database.
     */
    @Override
    public void close() {
        instances.remove(file.getAbsolutePath());
        db.close();
    }
}
