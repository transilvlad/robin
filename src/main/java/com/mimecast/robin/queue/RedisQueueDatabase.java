package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed scheduled work queue.
 *
 * @param <T> payload type
 */
public class RedisQueueDatabase<T extends Serializable> implements QueueDatabase<T> {
    private static final Logger log = LogManager.getLogger(RedisQueueDatabase.class);

    private JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String queueKey;

    public RedisQueueDatabase() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig redisConfig = new BasicConfig(queueConfig.getMapProperty("queueRedis"));
        this.host = redisConfig.getStringProperty("host", "localhost");
        this.port = Math.toIntExact(redisConfig.getLongProperty("port", 6379L));
        this.queueKey = redisConfig.getStringProperty("queueKey", "robin:queue");
    }

    @Override
    public void initialize() {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            this.jedisPool = new JedisPool(poolConfig, host, port);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Redis queue database", e);
        }
    }

    @Override
    public QueueItem<T> enqueue(QueueItem<T> item) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            enqueuePipeline(pipeline, item.readyAt(item.getNextAttemptAtEpochSeconds()).syncFromPayload());
            pipeline.sync();
            return item;
        } catch (Exception e) {
            log.error("Failed to enqueue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    @Override
    public void applyMutations(QueueMutationBatch<T> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();

            for (QueueMutation<T> mutation : batch.mutations()) {
                if (mutation == null || mutation.item() == null) {
                    continue;
                }

                QueueItem<T> item = mutation.item();
                switch (mutation.type()) {
                    case ACK -> deletePipeline(pipeline, item.getUid());
                    case RESCHEDULE -> {
                        item.readyAt(mutation.nextAttemptAtEpochSeconds())
                                .setLastError(mutation.lastError())
                                .syncFromPayload();
                        pipeline.set(payloadKey(item.getUid()).getBytes(), QueuePayloadCodec.serialize(item.getPayload()));
                        pipeline.hset(metaKey(item.getUid()), encodeMeta(item));
                        pipeline.zrem(claimedKey(), item.getUid());
                        pipeline.zrem(deadKey(), item.getUid());
                        pipeline.zadd(readyKey(), item.getNextAttemptAtEpochSeconds(), item.getUid());
                        pipeline.zadd(createdKey(), item.getCreatedAtEpochSeconds(), item.getUid());
                    }
                    case DEAD -> {
                        item.dead(mutation.lastError()).syncFromPayload();
                        pipeline.set(payloadKey(item.getUid()).getBytes(), QueuePayloadCodec.serialize(item.getPayload()));
                        pipeline.hset(metaKey(item.getUid()), encodeMeta(item));
                        pipeline.zrem(readyKey(), item.getUid());
                        pipeline.zrem(claimedKey(), item.getUid());
                        pipeline.zadd(deadKey(), item.getCreatedAtEpochSeconds(), item.getUid());
                        pipeline.zadd(createdKey(), item.getCreatedAtEpochSeconds(), item.getUid());
                    }
                }
            }

            for (T newItem : batch.newItems()) {
                enqueuePipeline(pipeline, QueueItem.ready(newItem));
            }

            pipeline.sync();
        } catch (Exception e) {
            log.error("Failed to apply queue mutations: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to apply queue mutations", e);
        }
    }

    @Override
    public List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds) {
        if (limit <= 0) {
            return List.of();
        }

        String script = """
                local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
                for _, id in ipairs(ids) do
                  redis.call('ZREM', KEYS[1], id)
                  redis.call('ZADD', KEYS[2], ARGV[3], id)
                  redis.call('HSET', ARGV[4] .. id .. ':meta',
                    'state', ARGV[5],
                    'claimOwner', ARGV[6],
                    'claimedUntil', ARGV[3],
                    'updatedAt', ARGV[1])
                end
                return ids
                """;

        try (Jedis jedis = jedisPool.getResource()) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) jedis.eval(script, List.of(readyKey(), claimedKey()),
                    List.of(String.valueOf(nowEpochSeconds), String.valueOf(limit), String.valueOf(claimUntilEpochSeconds),
                            keyPrefix(), QueueItemState.CLAIMED.name(), consumerId));
            if (ids.isEmpty()) {
                return List.of();
            }

            Pipeline pipeline = jedis.pipelined();
            List<Response<Map<String, String>>> metaResponses = new ArrayList<>(ids.size());
            List<Response<byte[]>> payloadResponses = new ArrayList<>(ids.size());
            for (String uid : ids) {
                metaResponses.add(pipeline.hgetAll(metaKey(uid)));
                payloadResponses.add(pipeline.get(payloadKey(uid).getBytes()));
            }
            pipeline.sync();

            List<QueueItem<T>> claimed = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                Map<String, String> meta = metaResponses.get(i).get();
                byte[] payload = payloadResponses.get(i).get();
                if (meta == null || meta.isEmpty() || payload == null) {
                    continue;
                }
                claimed.add(decodeItem(meta, payload));
            }
            return claimed;
        } catch (Exception e) {
            log.error("Failed to claim ready items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to claim ready items", e);
        }
    }

    @Override
    public boolean acknowledge(String uid) {
        return deleteByUID(uid);
    }

    @Override
    public boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        try {
            boolean exists = getByUID(item.getUid()) != null;
            applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.reschedule(item, nextAttemptAtEpochSeconds, lastError)), List.of()));
            return exists;
        } catch (Exception e) {
            log.error("Failed to reschedule item {}: {}", item.getUid(), e.getMessage(), e);
            throw new RuntimeException("Failed to reschedule item", e);
        }
    }

    @Override
    public int releaseExpiredClaims(long nowEpochSeconds) {
        String script = """
                local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
                for _, id in ipairs(ids) do
                  redis.call('ZREM', KEYS[1], id)
                  redis.call('ZADD', KEYS[2], ARGV[1], id)
                  redis.call('HSET', ARGV[2] .. id .. ':meta',
                    'state', ARGV[3],
                    'claimOwner', '',
                    'claimedUntil', '0',
                    'nextAttemptAt', ARGV[1],
                    'updatedAt', ARGV[1])
                end
                return #ids
                """;
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(script, List.of(claimedKey(), readyKey()),
                    List.of(String.valueOf(nowEpochSeconds), keyPrefix(), QueueItemState.READY.name()));
            return ((Number) result).intValue();
        } catch (Exception e) {
            log.error("Failed to release expired claims: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to release expired claims", e);
        }
    }

    @Override
    public boolean markDead(String uid, String lastError) {
        QueueItem<T> item = getByUID(uid);
        if (item == null) {
            return false;
        }
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.dead(item, lastError)), List.of()));
        return true;
    }

    @Override
    public long size() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zcard(readyKey()) + jedis.zcard(claimedKey());
        } catch (Exception e) {
            log.error("Failed to get queue size: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get queue size", e);
        }
    }

    @Override
    public QueueStats stats() {
        try (Jedis jedis = jedisPool.getResource()) {
            long ready = jedis.zcard(readyKey());
            long claimed = jedis.zcard(claimedKey());
            long dead = jedis.zcard(deadKey());
            long oldestReady = readFirstScore(jedis, readyKey());
            long oldestClaimed = readFirstScore(jedis, claimedKey());
            return new QueueStats(ready, claimed, dead, ready + claimed, oldestReady, oldestClaimed);
        } catch (Exception e) {
            log.error("Failed to get queue stats: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get queue stats", e);
        }
    }

    @Override
    public QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        QueueListFilter effectiveFilter = filter != null ? filter : QueueListFilter.activeOnly();
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrange(createdKey(), 0, -1);
            List<QueueItem<T>> matches = new ArrayList<>();
            for (String uid : ids) {
                QueueItem<T> item = getByUID(uid);
                if (item != null && effectiveFilter.matches(item)) {
                    matches.add(item);
                }
            }
            int safeOffset = Math.max(0, offset);
            int safeLimit = Math.max(0, limit);
            int end = Math.min(matches.size(), safeOffset + safeLimit);
            List<QueueItem<T>> page = safeOffset >= matches.size() ? List.of() : new ArrayList<>(matches.subList(safeOffset, end));
            return new QueuePage<>(matches.size(), page);
        } catch (Exception e) {
            log.error("Failed to list queue items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to list queue items", e);
        }
    }

    @Override
    public QueueItem<T> getByUID(String uid) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> meta = jedis.hgetAll(metaKey(uid));
            byte[] payload = jedis.get(payloadKey(uid).getBytes());
            if (meta == null || meta.isEmpty() || payload == null) {
                return null;
            }
            return decodeItem(meta, payload);
        } catch (Exception e) {
            log.error("Failed to get queue item {}: {}", uid, e.getMessage(), e);
            throw new RuntimeException("Failed to get queue item", e);
        }
    }

    @Override
    public boolean deleteByUID(String uid) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            boolean exists = jedis.exists(metaKey(uid)) || jedis.exists(payloadKey(uid).getBytes());
            if (!exists) {
                return false;
            }
            Pipeline pipeline = jedis.pipelined();
            deletePipeline(pipeline, uid);
            pipeline.sync();
            return true;
        } catch (Exception e) {
            log.error("Failed to delete queue item {}: {}", uid, e.getMessage(), e);
            throw new RuntimeException("Failed to delete queue item", e);
        }
    }

    @Override
    public int deleteByUIDs(List<String> uids) {
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
    public void clear() {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrange(createdKey(), 0, -1);
            Pipeline pipeline = jedis.pipelined();
            for (String uid : ids) {
                pipeline.del(payloadKey(uid));
                pipeline.del(metaKey(uid));
            }
            pipeline.del(createdKey(), readyKey(), claimedKey(), deadKey());
            pipeline.sync();
        } catch (Exception e) {
            log.error("Failed to clear queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear queue", e);
        }
    }

    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }

    private void enqueuePipeline(Pipeline pipeline, QueueItem<T> item) {
        String uid = item.getUid();
        pipeline.del(payloadKey(uid));
        pipeline.del(metaKey(uid));
        pipeline.set(payloadKey(uid).getBytes(), QueuePayloadCodec.serialize(item.getPayload()));
        pipeline.hset(metaKey(uid), encodeMeta(item));
        pipeline.zadd(createdKey(), item.getCreatedAtEpochSeconds(), uid);
        pipeline.zrem(claimedKey(), uid);
        pipeline.zrem(deadKey(), uid);
        pipeline.zadd(readyKey(), item.getNextAttemptAtEpochSeconds(), uid);
    }

    private void deletePipeline(Pipeline pipeline, String uid) {
        pipeline.del(payloadKey(uid));
        pipeline.del(metaKey(uid));
        pipeline.zrem(createdKey(), uid);
        pipeline.zrem(readyKey(), uid);
        pipeline.zrem(claimedKey(), uid);
        pipeline.zrem(deadKey(), uid);
    }

    private Map<String, String> encodeMeta(QueueItem<T> item) {
        Map<String, String> meta = new HashMap<>();
        meta.put("uid", item.getUid());
        meta.put("state", item.getState().name());
        meta.put("createdAt", String.valueOf(item.getCreatedAtEpochSeconds()));
        meta.put("updatedAt", String.valueOf(item.getUpdatedAtEpochSeconds()));
        meta.put("nextAttemptAt", String.valueOf(item.getNextAttemptAtEpochSeconds()));
        meta.put("claimedUntil", String.valueOf(item.getClaimedUntilEpochSeconds()));
        meta.put("claimOwner", item.getClaimOwner() == null ? "" : item.getClaimOwner());
        meta.put("retryCount", String.valueOf(item.getRetryCount()));
        meta.put("protocol", item.getProtocol() == null ? "" : item.getProtocol());
        meta.put("sessionUid", item.getSessionUid() == null ? "" : item.getSessionUid());
        meta.put("lastError", item.getLastError() == null ? "" : item.getLastError());
        return meta;
    }

    private QueueItem<T> decodeItem(Map<String, String> meta, byte[] payloadBytes) {
        QueueItem<T> item = QueueItem.restore(
                meta.get("uid"),
                parseLong(meta.get("createdAt")),
                QueuePayloadCodec.deserialize(payloadBytes)
        );
        item.setState(QueueItemState.valueOf(meta.getOrDefault("state", QueueItemState.READY.name())));
        item.setUpdatedAtEpochSeconds(parseLong(meta.get("updatedAt")));
        item.setNextAttemptAtEpochSeconds(parseLong(meta.get("nextAttemptAt")));
        item.setClaimedUntilEpochSeconds(parseLong(meta.get("claimedUntil")));
        item.setClaimOwner(blankToNull(meta.get("claimOwner")));
        item.setRetryCount((int) parseLong(meta.get("retryCount")));
        item.setProtocol(blankToNull(meta.get("protocol")));
        item.setSessionUid(blankToNull(meta.get("sessionUid")));
        item.setLastError(blankToNull(meta.get("lastError")));
        item.syncFromPayload();
        return item;
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long readFirstScore(Jedis jedis, String zsetKey) {
        List<redis.clients.jedis.resps.Tuple> tuples = jedis.zrangeWithScores(zsetKey, 0, 0);
        if (tuples.isEmpty()) {
            return 0L;
        }
        return (long) tuples.getFirst().getScore();
    }

    private String keyPrefix() {
        return queueKey + ':';
    }

    private String payloadKey(String uid) {
        return keyPrefix() + uid + ":payload";
    }

    private String metaKey(String uid) {
        return keyPrefix() + uid + ":meta";
    }

    private String createdKey() {
        return queueKey + ":created";
    }

    private String readyKey() {
        return queueKey + ":ready";
    }

    private String claimedKey() {
        return queueKey + ":claimed";
    }

    private String deadKey() {
        return queueKey + ":dead";
    }
}
