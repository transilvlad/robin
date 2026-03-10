package com.mimecast.robin.queue;

import java.io.Closeable;
import java.io.Serializable;
import java.util.List;

/**
 * Contract for a scheduled work queue with claim/ack semantics.
 *
 * @param <T> payload type
 */
public interface QueueDatabase<T extends Serializable> extends Closeable {

    /**
     * Initialize external resources.
     */
    void initialize();

    /**
     * Inserts a ready queue item.
     */
    QueueItem<T> enqueue(QueueItem<T> item);

    /**
     * Applies a batch of dequeue outcomes and derived enqueues atomically when supported.
     */
    void applyMutations(QueueMutationBatch<T> batch);

    /**
     * Claims ready items for the given consumer until the lease expires.
     */
    List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds);

    /**
     * Acknowledges successful completion and removes the item from the active queue.
     */
    boolean acknowledge(String uid);

    /**
     * Reschedules a claimed item for a future attempt.
     */
    boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError);

    /**
     * Releases expired claims back to READY.
     */
    int releaseExpiredClaims(long nowEpochSeconds);

    /**
     * Marks an item as dead.
     */
    boolean markDead(String uid, String lastError);

    /**
     * Active queue size.
     */
    long size();

    /**
     * Aggregate queue statistics.
     */
    QueueStats stats();

    /**
     * Returns a paged queue listing ordered by creation time.
     */
    QueuePage<T> list(int offset, int limit, QueueListFilter filter);

    /**
     * Returns the item by queue UID.
     */
    QueueItem<T> getByUID(String uid);

    /**
     * Removes the item by queue UID regardless of state.
     */
    boolean deleteByUID(String uid);

    /**
     * Removes multiple items by queue UID regardless of state.
     */
    int deleteByUIDs(List<String> uids);

    /**
     * Clears the full queue state, including dead items.
     */
    void clear();
}
