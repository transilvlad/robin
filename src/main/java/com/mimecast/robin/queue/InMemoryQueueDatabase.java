package com.mimecast.robin.queue;

import java.io.Serializable;
import java.time.Instant;
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
    private final NavigableMap<String, String> createdIndex = new TreeMap<>();
    private final NavigableMap<String, String> readyIndex = new TreeMap<>();
    private final NavigableMap<String, String> claimedIndex = new TreeMap<>();

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
    public synchronized List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId,
                                                      long claimUntilEpochSeconds) {
        List<QueueItem<T>> claimed = new ArrayList<>();
        if (limit <= 0) {
            return claimed;
        }

        List<String> keys = new ArrayList<>(readyIndex.headMap(sortKey(nowEpochSeconds, Long.MAX_VALUE, "~"), true).keySet());
        for (String key : keys) {
            if (claimed.size() >= limit) {
                break;
            }
            String uid = readyIndex.remove(key);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.READY) {
                continue;
            }
            item.claim(consumerId, claimUntilEpochSeconds);
            claimedIndex.put(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
            claimed.add(item);
        }
        return claimed;
    }

    @Override
    public synchronized boolean acknowledge(String uid) {
        return deleteByUID(uid);
    }

    @Override
    public synchronized boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        QueueItem<T> existing = items.get(item.getUid());
        if (existing == null) {
            return false;
        }
        removeIndexes(existing);
        existing.setPayload(item.getPayload())
                .setRetryCount(item.getRetryCount())
                .setProtocol(item.getProtocol())
                .setSessionUid(item.getSessionUid())
                .setLastError(lastError)
                .readyAt(nextAttemptAtEpochSeconds);
        upsert(existing);
        return true;
    }

    @Override
    public synchronized int releaseExpiredClaims(long nowEpochSeconds) {
        int released = 0;
        List<String> keys = new ArrayList<>(claimedIndex.headMap(sortKey(nowEpochSeconds, Long.MAX_VALUE, "~"), true).keySet());
        for (String key : keys) {
            String uid = claimedIndex.remove(key);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.CLAIMED) {
                continue;
            }
            item.readyAt(nowEpochSeconds);
            readyIndex.put(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
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
        removeIndexes(item);
        item.dead(lastError);
        createdIndex.put(sortKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
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
        long oldestReady = readyIndex.isEmpty() ? 0L : parseEpoch(readyIndex.firstKey());
        long oldestClaimed = claimedIndex.isEmpty() ? 0L : parseEpoch(claimedIndex.firstKey());
        return new QueueStats(ready, claimed, dead, ready + claimed, oldestReady, oldestClaimed);
    }

    @Override
    public synchronized QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        List<QueueItem<T>> all = new ArrayList<>();
        for (String key : createdIndex.keySet()) {
            QueueItem<T> item = items.get(createdIndex.get(key));
            if (item != null && (filter == null || filter.matches(item))) {
                all.add(item);
            }
        }

        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        int end = Math.min(all.size(), safeOffset + safeLimit);
        List<QueueItem<T>> slice = safeOffset >= all.size() ? List.of() : new ArrayList<>(all.subList(safeOffset, end));
        return new QueuePage<>(all.size(), slice);
    }

    @Override
    public synchronized QueueItem<T> getByUID(String uid) {
        return items.get(uid);
    }

    @Override
    public synchronized boolean deleteByUID(String uid) {
        QueueItem<T> item = items.remove(uid);
        if (item == null) {
            return false;
        }
        removeIndexes(item);
        return true;
    }

    @Override
    public synchronized int deleteByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String uid : new LinkedHashSet<>(uids)) {
            if (deleteByUID(uid)) {
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
        createdIndex.put(sortKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        if (item.getState() == QueueItemState.READY) {
            readyIndex.put(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        } else if (item.getState() == QueueItemState.CLAIMED) {
            claimedIndex.put(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        }
    }

    private void removeIndexes(QueueItem<T> item) {
        createdIndex.remove(sortKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        readyIndex.remove(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        claimedIndex.remove(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
    }

    private static String sortKey(long primaryEpochSeconds, long secondaryEpochSeconds, String uid) {
        return String.format("%020d|%020d|%s", primaryEpochSeconds, secondaryEpochSeconds, uid);
    }

    private static long parseEpoch(String key) {
        int end = key.indexOf('|');
        return end < 0 ? Instant.now().getEpochSecond() : Long.parseLong(key.substring(0, end));
    }
}
