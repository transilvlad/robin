package com.mimecast.robin.queue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.Atomic;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerJava;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * MapDB-backed scheduled work queue.
 *
 * @param <T> payload type
 */
public class MapDBQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private static final Logger log = LogManager.getLogger(MapDBQueueDatabase.class);

    private final File file;
    private final int concurrencyScale;
    private DB db;
    private BTreeMap<String, QueueItem<T>> items;
    private BTreeMap<String, String> createdIndex;
    private BTreeMap<String, String> readyIndex;
    private BTreeMap<String, String> claimedIndex;
    private Atomic.Long deadCount;

    public MapDBQueueDatabase(File file, int concurrencyScale) {
        this.file = file;
        this.concurrencyScale = concurrencyScale;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void initialize() {
        boolean isTempFile = file.getAbsolutePath().contains("temp")
                || file.getAbsolutePath().contains("junit")
                || file.getName().startsWith("test-");

        DBMaker.Maker dbMaker = DBMaker
                .fileDB(file)
                .concurrencyScale(concurrencyScale)
                .closeOnJvmShutdown();

        if (isTempFile) {
            this.db = dbMaker.fileLockDisable().fileChannelEnable().make();
        } else {
            this.db = dbMaker.fileMmapEnableIfSupported().transactionEnable().make();
        }

        SerializerJava serializer = new SerializerJava();
        this.items = (BTreeMap<String, QueueItem<T>>) db.treeMap("queue_items", Serializer.STRING, serializer).createOrOpen();
        this.createdIndex = db.treeMap("queue_created_index", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.readyIndex = db.treeMap("queue_ready_index", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.claimedIndex = db.treeMap("queue_claimed_index", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.deadCount = db.atomicLong("queue_dead_count").createOrOpen();
    }

    @Override
    public synchronized QueueItem<T> enqueue(QueueItem<T> item) {
        removeIndexes(item);
        item.readyAt(item.getNextAttemptAtEpochSeconds()).syncFromPayload();
        putItem(item);
        commit();
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
                        putItem(existing);
                    }
                }
                case DEAD -> {
                    QueueItem<T> existing = items.get(item.getUid());
                    if (existing != null) {
                        removeIndexes(existing);
                        if (existing.getState() != QueueItemState.DEAD) {
                            deadCount.set(deadCount.get() + 1L);
                        }
                        existing.setPayload(item.getPayload())
                                .setRetryCount(item.getRetryCount())
                                .setProtocol(item.getProtocol())
                                .setSessionUid(item.getSessionUid())
                                .dead(mutation.lastError());
                        putItem(existing);
                    }
                }
            }
        }

        for (T newItem : batch.newItems()) {
            QueueItem<T> queueItem = QueueItem.ready(newItem);
            removeIndexes(queueItem);
            putItem(queueItem);
        }

        commit();
    }

    @Override
    public synchronized List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId,
                                                      long claimUntilEpochSeconds) {
        List<QueueItem<T>> claimed = new ArrayList<>();
        if (limit <= 0) {
            return claimed;
        }

        String toKey = sortKey(nowEpochSeconds, Long.MAX_VALUE, "~");
        List<String> readyKeys = new ArrayList<>(readyIndex.headMap(toKey, true).keySet());
        for (String readyKey : readyKeys) {
            if (claimed.size() >= limit) {
                break;
            }
            String uid = readyIndex.remove(readyKey);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.READY) {
                continue;
            }
            item.claim(consumerId, claimUntilEpochSeconds);
            items.put(uid, item);
            claimedIndex.put(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
            claimed.add(copyItem(item));
        }
        commit();
        return claimed;
    }

    @Override
    public synchronized boolean acknowledge(String uid) {
        boolean removed = deleteInternal(uid);
        if (removed) {
            commit();
        }
        return removed;
    }

    @Override
    public synchronized boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        boolean exists = items.containsKey(item.getUid());
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.reschedule(item, nextAttemptAtEpochSeconds, lastError)), List.of()));
        return exists;
    }

    @Override
    public synchronized int releaseExpiredClaims(long nowEpochSeconds) {
        int released = 0;
        String toKey = sortKey(nowEpochSeconds, Long.MAX_VALUE, "~");
        List<String> claimKeys = new ArrayList<>(claimedIndex.headMap(toKey, true).keySet());
        for (String claimKey : claimKeys) {
            String uid = claimedIndex.remove(claimKey);
            QueueItem<T> item = items.get(uid);
            if (item == null || item.getState() != QueueItemState.CLAIMED) {
                continue;
            }
            item.readyAt(nowEpochSeconds);
            items.put(uid, item);
            readyIndex.put(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), uid), uid);
            released++;
        }
        if (released > 0) {
            commit();
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
        return readyIndex.sizeLong() + claimedIndex.sizeLong();
    }

    @Override
    public synchronized QueueStats stats() {
        long ready = readyIndex.sizeLong();
        long claimed = claimedIndex.sizeLong();
        long dead = deadCount.get();
        long oldestReady = readyIndex.isEmpty() ? 0L : parseEpoch(readyIndex.firstKey());
        long oldestClaimed = claimedIndex.isEmpty() ? 0L : parseEpoch(claimedIndex.firstKey());
        return new QueueStats(ready, claimed, dead, ready + claimed, oldestReady, oldestClaimed);
    }

    @Override
    public synchronized QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        List<QueueItem<T>> all = new ArrayList<>();
        for (String createdKey : createdIndex.keySet()) {
            QueueItem<T> item = items.get(createdIndex.get(createdKey));
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
        boolean removed = deleteInternal(uid);
        if (removed) {
            commit();
        }
        return removed;
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
        if (removed > 0) {
            commit();
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        items.clear();
        createdIndex.clear();
        readyIndex.clear();
        claimedIndex.clear();
        deadCount.set(0L);
        commit();
    }

    @Override
    public synchronized void close() {
        if (db != null) {
            try {
                if (!db.isClosed()) {
                    db.commit();
                    db.close();
                }
            } catch (Exception e) {
                log.warn("Error closing MapDB database for file {}: {}", file.getAbsolutePath(), e.getMessage());
            } finally {
                db = null;
            }
        }
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

    private void putItem(QueueItem<T> item) {
        items.put(item.getUid(), item);
        createdIndex.put(sortKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        if (item.getState() == QueueItemState.READY) {
            readyIndex.put(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        } else if (item.getState() == QueueItemState.CLAIMED) {
            claimedIndex.put(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()), item.getUid());
        }
    }

    private boolean deleteInternal(String uid) {
        QueueItem<T> item = items.remove(uid);
        if (item == null) {
            return false;
        }
        if (item.getState() == QueueItemState.DEAD) {
            deadCount.set(Math.max(0L, deadCount.get() - 1L));
        }
        removeIndexes(item);
        return true;
    }

    private void removeIndexes(QueueItem<T> item) {
        createdIndex.remove(sortKey(item.getCreatedAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        readyIndex.remove(sortKey(item.getNextAttemptAtEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
        claimedIndex.remove(sortKey(item.getClaimedUntilEpochSeconds(), item.getCreatedAtEpochSeconds(), item.getUid()));
    }

    private void commit() {
        if (db != null && !db.isClosed()) {
            db.commit();
        }
    }

    private static String sortKey(long primaryEpochSeconds, long secondaryEpochSeconds, String uid) {
        return String.format("%020d|%020d|%s", primaryEpochSeconds, secondaryEpochSeconds, uid);
    }

    private static long parseEpoch(String key) {
        int end = key.indexOf('|');
        return end < 0 ? 0L : Long.parseLong(key.substring(0, end));
    }
}
