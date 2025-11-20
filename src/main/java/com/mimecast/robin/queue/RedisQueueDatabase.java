package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.*;
import java.util.*;

/**
 * Redis implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by Redis LIST with Java serialization.
 * <p>Compatible with AWS Elasticache and standard Redis instances.
 * <p>Configuration is loaded from {@code Config.getServer().getQueue().getMapProperty("queueRedis")}.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class RedisQueueDatabase<T extends Serializable> implements QueueDatabase<T> {

    private static final Logger log = LogManager.getLogger(RedisQueueDatabase.class);
    
    // Placeholder prefixes for atomic remove operations
    private static final String REMOVE_PLACEHOLDER_PREFIX = "__ROBIN_REMOVE__";
    private static final String REMOVE_UID_PLACEHOLDER_PREFIX = "__ROBIN_REMOVE_UID__";
    private static final String REMOVE_UIDS_PLACEHOLDER_PREFIX = "__ROBIN_REMOVE_UIDS__";

    private JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String queueKey;

    /**
     * Constructs a new RedisQueueDatabase instance.
     * <p>Loads configuration including host, port, and queueKey from queue config.
     */
    public RedisQueueDatabase() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig redisConfig = new BasicConfig(queueConfig.getMapProperty("queueRedis"));

        this.host = redisConfig.getStringProperty("host", "localhost");
        this.port = Math.toIntExact(redisConfig.getLongProperty("port", 6379L));
        this.queueKey = redisConfig.getStringProperty("queueKey", "robin:queue");
    }

    /**
     * Initialize the Redis connection pool.
     */
    @Override
    public void initialize() {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);

            this.jedisPool = new JedisPool(poolConfig, host, port);

            // Test the connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            log.info("Redis queue database initialized: host={}, port={}, queueKey={}", host, port, queueKey);
        } catch (Exception e) {
            log.error("Failed to initialize Redis queue database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Redis queue database", e);
        }
    }

    /**
     * Add an item to the tail of the queue.
     * <p>Uses Redis LPUSH to push to the left (tail) of the list.
     *
     * @param item The item to enqueue
     */
    @Override
    public void enqueue(T item) {
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] serialized = serialize(item);
            jedis.lpush(queueKey.getBytes(), serialized);
        } catch (Exception e) {
            log.error("Failed to enqueue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    /**
     * Remove and return the head of the queue, or null if empty.
     * <p>Uses Redis RPOP to pop from the right (head) of the list.
     *
     * @return The head item or null if empty
     */
    @Override
    public T dequeue() {
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] data = jedis.rpop(queueKey.getBytes());
            if (data == null) {
                return null;
            }
            return deserialize(data);
        } catch (Exception e) {
            log.error("Failed to dequeue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to dequeue item", e);
        }
    }

    /**
     * Peek at the head without removing.
     * <p>Uses Redis LINDEX to get the rightmost (head) element at index -1.
     *
     * @return The head item or null if empty
     */
    @Override
    public T peek() {
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] data = jedis.lindex(queueKey.getBytes(), -1);
            if (data == null) {
                return null;
            }
            return deserialize(data);
        } catch (Exception e) {
            log.error("Failed to peek item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to peek item", e);
        }
    }

    /**
     * Check if the queue is empty.
     *
     * @return true if the queue is empty
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Get the size of the queue.
     * <p>Uses Redis LLEN to get the length of the list.
     *
     * @return The number of items in the queue
     */
    @Override
    public long size() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen(queueKey.getBytes());
        } catch (Exception e) {
            log.error("Failed to get queue size: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get queue size", e);
        }
    }

    /**
     * Take a snapshot copy of current values for read-only inspection.
     * <p>Uses Redis LRANGE to get all items in the list.
     *
     * @return List of all items in the queue
     */
    @Override
    public List<T> snapshot() {
        try (Jedis jedis = jedisPool.getResource()) {
            long length = jedis.llen(queueKey.getBytes());
            if (length == 0) {
                return new ArrayList<>();
            }

            // LRANGE returns items in order from left to right (tail to head)
            // We need to reverse to match the queue order (head to tail)
            List<byte[]> data = jedis.lrange(queueKey.getBytes(), 0, -1);
            List<T> items = new ArrayList<>();
            for (int i = data.size() - 1; i >= 0; i--) {
                items.add(deserialize(data.get(i)));
            }
            return items;
        } catch (Exception e) {
            log.error("Failed to take snapshot: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to take snapshot", e);
        }
    }

    /**
     * Remove an item from the queue by index (0-based).
     * <p>Index 0 is the head of the queue (rightmost in Redis list).
     *
     * @param index The index of the item to remove
     * @return true if item was removed, false if index was out of bounds
     */
    @Override
    public boolean removeByIndex(int index) {
        if (index < 0) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            long length = jedis.llen(queueKey.getBytes());
            if (index >= length) {
                return false;
            }

            // Convert queue index to Redis list index (reverse order)
            long redisIndex = length - 1 - index;

            // Get the item at the index
            byte[] data = jedis.lindex(queueKey.getBytes(), redisIndex);
            if (data == null) {
                return false;
            }

            // Use a unique placeholder to mark and remove the item
            String placeholder = REMOVE_PLACEHOLDER_PREFIX + UUID.randomUUID();
            jedis.lset(queueKey.getBytes(), redisIndex, placeholder.getBytes());
            jedis.lrem(queueKey.getBytes(), 1, placeholder.getBytes());

            return true;
        } catch (Exception e) {
            log.error("Failed to remove by index: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by index", e);
        }
    }

    /**
     * Remove items from the queue by indices (0-based).
     * <p>Indices are processed in descending order to avoid index shifting issues.
     *
     * @param indices The indices of items to remove
     * @return Number of items successfully removed
     */
    @Override
    public int removeByIndices(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return 0;
        }

        // Sort indices in descending order to avoid index shifting issues
        List<Integer> sortedIndices = new ArrayList<>(indices);
        sortedIndices.sort(Comparator.reverseOrder());

        int removed = 0;
        for (int index : sortedIndices) {
            if (removeByIndex(index)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Remove an item from the queue by UID (for RelaySession).
     *
     * @param uid The UID of the item to remove
     * @return true if item was removed, false if not found
     */
    @Override
    public boolean removeByUID(String uid) {
        if (uid == null) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            List<byte[]> data = jedis.lrange(queueKey.getBytes(), 0, -1);
            
            for (int i = 0; i < data.size(); i++) {
                T item = deserialize(data.get(i));
                if (item instanceof RelaySession relaySession) {
                    if (uid.equals(relaySession.getUID())) {
                        // Use a unique placeholder to mark and remove the item
                        String placeholder = REMOVE_UID_PLACEHOLDER_PREFIX + UUID.randomUUID();
                        jedis.lset(queueKey.getBytes(), i, placeholder.getBytes());
                        jedis.lrem(queueKey.getBytes(), 1, placeholder.getBytes());
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to remove by UID: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by UID", e);
        }
    }

    /**
     * Remove items from the queue by UIDs (for RelaySession).
     *
     * @param uids The UIDs of items to remove
     * @return Number of items successfully removed
     */
    @Override
    public int removeByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }

        Set<String> uidSet = new HashSet<>(uids);
        int removed = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            List<byte[]> data = jedis.lrange(queueKey.getBytes(), 0, -1);
            List<Integer> indicesToRemove = new ArrayList<>();

            for (int i = 0; i < data.size(); i++) {
                T item = deserialize(data.get(i));
                if (item instanceof RelaySession relaySession) {
                    if (uidSet.contains(relaySession.getUID())) {
                        indicesToRemove.add(i);
                    }
                }
            }

            // Remove in reverse order to avoid index shifting
            for (int i = indicesToRemove.size() - 1; i >= 0; i--) {
                int index = indicesToRemove.get(i);
                String placeholder = REMOVE_UIDS_PLACEHOLDER_PREFIX + UUID.randomUUID();
                jedis.lset(queueKey.getBytes(), index, placeholder.getBytes());
                jedis.lrem(queueKey.getBytes(), 1, placeholder.getBytes());
                removed++;
            }

            return removed;
        } catch (Exception e) {
            log.error("Failed to remove by UIDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by UIDs", e);
        }
    }

    /**
     * Clear all items from the queue.
     * <p>Uses Redis DEL to delete the entire list.
     */
    @Override
    public void clear() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(queueKey.getBytes());
        } catch (Exception e) {
            log.error("Failed to clear queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear queue", e);
        }
    }

    /**
     * Close the Redis connection pool.
     */
    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
                log.debug("Redis queue database connection pool closed");
            } catch (Exception e) {
                log.warn("Error closing Redis connection pool: {}", e.getMessage());
            }
        }
    }

    /**
     * Serialize an object to byte array using Java serialization.
     *
     * @param item Item to serialize
     * @return Serialized byte array
     */
    private byte[] serialize(T item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(item);
            return bos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to serialize item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    /**
     * Deserialize a byte array to object using Java deserialization.
     *
     * @param data Byte array to deserialize
     * @return Deserialized object
     */
    @SuppressWarnings("unchecked")
    private T deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            log.error("Failed to deserialize item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }
}
