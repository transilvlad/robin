package com.mimecast.robin.queue;

import com.mimecast.robin.main.Factories;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Persistent queue facade for relay sessions and queue administration.
 *
 * @param <T> payload type
 */
public class PersistentQueue<T extends Serializable> implements Closeable {

    private static final Logger log = LogManager.getLogger(PersistentQueue.class);

    private final QueueDatabase<T> database;

    private static PersistentQueue<RelaySession> instance;

    @SuppressWarnings("unchecked")
    public static synchronized PersistentQueue<RelaySession> getInstance() {
        if (instance == null) {
            instance = new PersistentQueue<>();
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private PersistentQueue() {
        this.database = (QueueDatabase<T>) Factories.getQueueDatabase();
    }

    /**
     * Enqueues a payload as a ready queue item.
     */
    public QueueItem<T> enqueue(T item) {
        return database.enqueue(QueueItem.ready(item));
    }

    /**
     * Applies a batch of dequeue outcomes and derived enqueues.
     */
    public void applyMutations(QueueMutationBatch<T> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        database.applyMutations(batch);
    }

    /**
     * Claims ready items.
     */
    public List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds) {
        return database.claimReady(limit, nowEpochSeconds, consumerId, claimUntilEpochSeconds);
    }

    /**
     * Acknowledges a completed item.
     */
    public boolean acknowledge(String uid) {
        return database.acknowledge(uid);
    }

    /**
     * Reschedules an item for a future retry.
     */
    public boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        return database.reschedule(item, nextAttemptAtEpochSeconds, lastError);
    }

    /**
     * Releases expired claims.
     */
    public int releaseExpiredClaims(long nowEpochSeconds) {
        return database.releaseExpiredClaims(nowEpochSeconds);
    }

    /**
     * Marks an item as dead.
     */
    public boolean markDead(String uid, String lastError) {
        return database.markDead(uid, lastError);
    }

    /**
     * Active queue size.
     */
    public long size() {
        return database.size();
    }

    public QueueStats stats() {
        return database.stats();
    }

    public QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        return database.list(offset, limit, filter);
    }

    public QueueItem<T> getByUID(String uid) {
        return database.getByUID(uid);
    }

    public boolean deleteByUID(String uid) {
        return database.deleteByUID(uid);
    }

    public int deleteByUIDs(List<String> uids) {
        return database.deleteByUIDs(uids);
    }

    public void clear() {
        database.clear();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    @SuppressWarnings("unchecked")
    public boolean retryNow(String uid) {
        QueueItem<T> item = database.getByUID(uid);
        if (item == null) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (item.getPayload() instanceof RelaySession relaySession) {
            relaySession.bumpRetryCount();
            item.setPayload((T) relaySession);
        } else {
            item.setRetryCount(item.getRetryCount() + 1);
        }
        item.setLastError(null);
        return database.reschedule(item, now, null);
    }

    @Override
    public synchronized void close() {
        try {
            database.close();
        } catch (Exception e) {
            log.error("Error closing queue database: {}", e.getMessage());
        } finally {
            instance = null;
        }
    }
}
