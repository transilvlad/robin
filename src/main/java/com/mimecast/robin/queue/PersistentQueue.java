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
     * Claims one READY item by UID; null when it is missing, claimed or dead.
     */
    public QueueItem<T> claimByUID(String uid, String consumerId, long claimUntilEpochSeconds) {
        return database.claimByUID(uid, consumerId, claimUntilEpochSeconds);
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

    /**
     * Makes an item ready for immediate delivery and bumps its retry count.
     *
     * <p>A READY item is claimed first, so it cannot be rescheduled under a worker that is
     * delivering it; an item a worker holds is left alone. A dead item is revived: workers never
     * touch dead items, so no claim is needed.
     *
     * @param uid Queue item UID.
     * @return True when the item was rescheduled; false when it is missing or being delivered.
     */
    @SuppressWarnings("unchecked")
    public boolean retryNow(String uid) {
        long now = Instant.now().getEpochSecond();
        QueueItem<T> item = database.claimByUID(uid, "admin-retry", now + 300);
        if (item == null) {
            item = database.getByUID(uid);
            if (item == null || item.getState() != QueueItemState.DEAD) {
                return false;
            }
        }
        if (item.getPayload() instanceof RelaySession relaySession) {
            relaySession.bumpRetryCount();
            item.setPayload((T) relaySession).setRetryCount(relaySession.getRetryCount());
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
