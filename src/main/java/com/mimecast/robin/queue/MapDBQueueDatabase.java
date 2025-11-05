package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.*;
import org.mapdb.serializer.SerializerJava;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MapDB implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by MapDB v3.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class MapDBQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private static final Logger log = LogManager.getLogger(MapDBQueueDatabase.class);

    private final File file;
    private DB db;
    private BTreeMap<Long, T> queue;
    private Atomic.Long seq;

    /**
     * Constructs a new MapDBQueueDatabase instance.
     *
     * @param file The file to store the database.
     */
    public MapDBQueueDatabase(File file) {
        this.file = file;
    }

    /**
     * Initialize the database connection/resources.
     */
    @Override
    public void initialize() {
        // Check if this is a temp file (used in tests) to configure MapDB appropriately.
        boolean isTempFile = file.getAbsolutePath().contains("temp") ||
                           file.getAbsolutePath().contains("junit") ||
                           file.getName().startsWith("test-");

        DBMaker.Maker dbMaker = DBMaker
                .fileDB(file)
                .concurrencyScale(Math.toIntExact(Config.getServer().getQueue().getLongProperty("concurrencyScale", 32L)))
                .closeOnJvmShutdown();

        if (isTempFile) {
            // For temp files (tests), use simpler configuration to avoid Windows file locking issues.
            this.db = dbMaker
                    .fileLockDisable()
                    .fileChannelEnable()
                    .make();
        } else {
            // For production files, use full-featured configuration.
            this.db = dbMaker
                    .fileMmapEnableIfSupported()
                    .transactionEnable()
                    .make();
        }

        this.seq = db.atomicLong("queue_seq").createOrOpen();
        this.queue = db.treeMap("queue_map", Serializer.LONG, new SerializerJava()).createOrOpen();
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @param item The item to enqueue
     */
    @Override
    public void enqueue(T item) {
        long id = seq.incrementAndGet();
        queue.put(id, item);
        db.commit();
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     */
    @Override
    public T dequeue() {
        Map.Entry<Long, T> first = queue.pollFirstEntry();
        if (first == null) return null;
        db.commit();
        return first.getValue();
    }

    /**
     * Peek at the head without removing.
     */
    @Override
    public T peek() {
        Map.Entry<Long, T> first = queue.firstEntry();
        return first != null ? first.getValue() : null;
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
        return queue.sizeLong();
    }

    /**
     * Take a snapshot copy of current values for read-only inspection (e.g., metrics/health).
     */
    @Override
    public List<T> snapshot() {
        return new ArrayList<>(queue.values());
    }

    /**
     * Close the database.
     */
    @Override
    public void close() {
        if (db != null) {
            try {
                // Ensure all transactions are committed before closing.
                if (!db.isClosed()) {
                    db.commit();
                    db.close();
                }
            } catch (Exception e) {
                // Log but don't throw - close should be idempotent and not fail.
                // This is especially important for MapDB WAL file cleanup on Windows.
                log.warn("Error closing MapDB database for file {}: {}", file.getAbsolutePath(), e.getMessage());
            } finally {
                db = null;
            }
        }
    }
}
