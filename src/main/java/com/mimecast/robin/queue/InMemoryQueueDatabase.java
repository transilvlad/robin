package com.mimecast.robin.queue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory scheduled work queue used in tests.
 *
 * @param <T> payload type
 */
public class InMemoryQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private final Map<String, QueueItem<T>> items = new LinkedHashMap<>();
    private final NavigableMap<IndexKey, String> createdIndex = new TreeMap<>();
    private final NavigableMap<IndexKey, String> readyIndex = new TreeMap<>();
    private final NavigableMap<IndexKey, String> claimedIndex = new TreeMap<>();

    @Override
    public synchronized void initialize() {
        // No-op.
    }

    @Override
    public synchronized QueueItem<T> enqueue(QueueItem<T> item) {
        upsert(item.readyAt(item.getNextAttemptAtEpochSeconds()).syncFromPayload());
        return item;
    }

    @Override
    public synchronized void applyMutations(QueueMutationBatch<T> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        for (QueueMutation<T> mutation : batch.mutations()) {
            if (mutation == null || mutation.item() == null) {
                continue;
            }

            QueueItem<T> item = mutation.item();
            switch (mutation.type()) {
                case ACK -> deleteInternal(item.getUid());
                case RESCHEDULE -> {
                    QueueItem<T> existing = items.get(item.getUid());
                    if (existing != null) {
                        removeIndexes(existing);
                        existing.setPayload(item.getPayload())
                                .setRetryCount(item.getRetryCount())
                                .setProtocol(item.getProtocol())
                                .setSessionUid(item.getSessionUid())
                                .setLastError(mutation.lastError())
                                .readyAt(mutation.nextAttemptAtEpochSeconds());
                        upsert(existing);
                    }
                }
                case DEAD -> {
                    QueueItem<T> existing = items.get(item.getUid());
                    if (existing != null) {
                        removeIndexes(existing);
                        existing.setPayload(item.getPayload())
                                .setRetryCount(item.getRetryCount())
                                .setProtocol(item.getProtocol())
                                .setSessionUid(item.getSessionUid())
                                .dead(mutation.lastError());
                        upsert(existing);
                    }
                }
            }
        }

        for (T newItem : batch.newItems()) {
            enqueue(QueueItem.ready(newItem));
        }
    }

    @Override
    public synchronized List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId,
                                                      long claimUntilEpochSeconds) {
        List<QueueItem<T>> claimed = new ArrayList<>();
        if (limit <= 0) {
            return claimed;
        }

        List<IndexKey> keys = new ArrayList<>(readyIndex.headMap(indexKey(nowEpochSeconds, Long.MAX_VALUE, "￿"), true).keySet());
        for (IndexKey key : keys) {
            if (claimed.size() >= limit) {
                break;
            }
            String uid = readyIndex.remove(key);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.READY) {
                continue;
            }
            item.claim(consumerId, claimUntilEpochSeconds);
            claimedIndex.put(indexKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
            claimed.add(copyItem(item));
        }
        return claimed;
    }

    @Override
    public synchronized boolean acknowledge(String uid) {
        QueueMutationBatch<T> batch = new QueueMutationBatch<>(
                List.of(QueueMutation.acknowledge(QueueItem.restore(uid, 0L, null))),
                List.of()
        );
        boolean existed = items.containsKey(uid);
        applyMutations(batch);
        return existed;
    }

    @Override
    public synchronized boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        boolean existed = items.containsKey(item.getUid());
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.reschedule(item, nextAttemptAtEpochSeconds, lastError)), List.of()));
        return existed;
    }

    @Override
    public synchronized int releaseExpiredClaims(long nowEpochSeconds) {
        int released = 0;
        List<IndexKey> keys = new ArrayList<>(claimedIndex.headMap(indexKey(nowEpochSeconds, Long.MAX_VALUE, "￿"), true).keySet());
        for (IndexKey key : keys) {
            String uid = claimedIndex.remove(key);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.CLAIMED) {
                continue;
            }
            item.readyAt(nowEpochSeconds);
            readyIndex.put(indexKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
            released++;
        }
        return released;
    }

    @Override
    public synchronized boolean markDead(String uid, String lastError) {
        QueueItem<T> item = items.get(uid);
        if (item == null) {
            return false;
        }
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.dead(item, lastError)), List.of()));
        return true;
    }

    @Override
    public synchronized long size() {
        return items.values().stream().filter(QueueItem::isActive).count();
    }

    @Override
    public synchronized QueueStats stats() {
        long ready = readyIndex.size();
        long claimed = claimedIndex.size();
        long dead = items.size() - ready - claimed;
        long oldestReady = readyIndex.isEmpty() ? 0L : readyIndex.firstKey().primaryEpochSeconds;
        long oldestClaimed = claimedIndex.isEmpty() ? 0L : claimedIndex.firstKey().primaryEpochSeconds;
        return new QueueStats(ready, claimed, dead, ready + claimed, oldestReady, oldestClaimed);
    }

    @Override
    public synchronized QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        List<QueueItem<T>> all = new ArrayList<>();
        for (IndexKey key : createdIndex.keySet()) {
            QueueItem<T> item = items.get(createdIndex.get(key));
            if (item != null && (filter == null || filter.matches(item))) {
                all.add(item);
            }
        }

        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        int end = Math.min(all.size(), safeOffset + safeLimit);
        List<QueueItem<T>> slice = safeOffset >= all.size() ? List.of() : new ArrayList<>(all.subList(safeOffset, end));
        return new QueuePage<>(all.size(), slice.stream().map(this::copyItem).toList());
    }

    @Override
    public synchronized QueueItem<T> getByUID(String uid) {
        QueueItem<T> item = items.get(uid);
        return item == null ? null : copyItem(item);
    }

    @Override
    public synchronized boolean deleteByUID(String uid) {
        return deleteInternal(uid);
    }

    @Override
    public synchronized int deleteByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String uid : new LinkedHashSet<>(uids)) {
            if (deleteInternal(uid)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        items.clear();
        createdIndex.clear();
        readyIndex.clear();
        claimedIndex.clear();
    }

    @Override
    public synchronized void close() {
        clear();
    }

    private void upsert(QueueItem<T> item) {
        items.put(item.getUid(), item);
        createdIndex.put(indexKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        if (item.getState() == QueueItemState.READY) {
            readyIndex.put(indexKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        } else if (item.getState() == QueueItemState.CLAIMED) {
            claimedIndex.put(indexKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        }
    }

    private boolean deleteInternal(String uid) {
        QueueItem<T> item = items.remove(uid);
        if (item == null) {
            return false;
        }
        removeIndexes(item);
        return true;
    }

    private void removeIndexes(QueueItem<T> item) {
        createdIndex.remove(indexKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        readyIndex.remove(indexKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        claimedIndex.remove(indexKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
    }

    private QueueItem<T> copyItem(QueueItem<T> item) {
        if (item == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        T payloadCopy = (T) QueuePayloadCodec.deserialize(QueuePayloadCodec.serialize(item.getPayload()));
        QueueItem<T> copy = QueueItem.restore(item.getUid(), item.getCreatedAtEpochSeconds(), payloadCopy);
        copy.setState(item.getState());
        copy.setUpdatedAtEpochSeconds(item.getUpdatedAtEpochSeconds());
        copy.setNextAttemptAtEpochSeconds(item.getNextAttemptAtEpochSeconds());
        copy.setClaimedUntilEpochSeconds(item.getClaimedUntilEpochSeconds());
        copy.setClaimOwner(item.getClaimOwner());
        copy.setRetryCount(item.getRetryCount());
        copy.setProtocol(item.getProtocol());
        copy.setSessionUid(item.getSessionUid());
        copy.setLastError(item.getLastError());
        return copy;
    }

    private static IndexKey indexKey(long primaryEpochSeconds, long secondaryEpochSeconds, String uid) {
        return new IndexKey(primaryEpochSeconds, secondaryEpochSeconds, uid);
    }

    private static final class IndexKey implements Comparable<IndexKey> {
        private final long primaryEpochSeconds;
        private final long secondaryEpochSeconds;
        private final String uid;

        private IndexKey(long primaryEpochSeconds, long secondaryEpochSeconds, String uid) {
            this.primaryEpochSeconds = primaryEpochSeconds;
            this.secondaryEpochSeconds = secondaryEpochSeconds;
            this.uid = uid;
        }

        @Override
        public int compareTo(IndexKey other) {
            int primaryCompare = Long.compare(primaryEpochSeconds, other.primaryEpochSeconds);
            if (primaryCompare != 0) {
                return primaryCompare;
            }

            int secondaryCompare = Long.compare(secondaryEpochSeconds, other.secondaryEpochSeconds);
            if (secondaryCompare != 0) {
                return secondaryCompare;
            }

            return uid.compareTo(other.uid);
        }
    }
}
