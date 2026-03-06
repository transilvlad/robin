package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.DistributedRateConfig;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.List;

/**
 * Redis-backed implementation of {@link ConnectionStore}.
 *
 * <p>Stores connection state in Redis so that multiple Robin instances in a cluster
 * share a consistent view of active connections and rate-window history.
 *
 * <p>Data model:
 * <ul>
 *   <li>{@code {prefix}total} — STRING: global active connection count (INCR/DECR)</li>
 *   <li>{@code {prefix}active:{ip}} — STRING: per-IP active count (INCR/DECR)</li>
 *   <li>{@code {prefix}history:{ip}} — SORTED SET: connection timestamps (score = epoch second)</li>
 *   <li>{@code {prefix}cmd:{ip}} — SORTED SET: command timestamps (score = epoch second)</li>
 * </ul>
 *
 * <p>All keys are assigned a configurable TTL to prevent unbounded Redis memory growth.
 * On any Redis error the operation is silently dropped and a metric counter is incremented
 * so that the server continues operating without interruption.
 */
public class RedisConnectionStore implements ConnectionStore {
    private static final Logger log = LogManager.getLogger(RedisConnectionStore.class);

    private final JedisPool jedisPool;
    private final String prefix;
    private final long ttlSeconds;

    /**
     * Constructs a new RedisConnectionStore using the provided configuration.
     *
     * @param config Distributed rate limiting configuration.
     */
    public RedisConnectionStore(DistributedRateConfig config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getPoolMaxTotal());
        poolConfig.setTestOnBorrow(true);

        String password = config.getPassword();
        if (password == null || password.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, config.getHost(), config.getPort());
        } else {
            this.jedisPool = new JedisPool(poolConfig, config.getHost(), config.getPort(), 2000, password);
        }

        this.prefix = config.getKeyPrefix();
        this.ttlSeconds = config.getKeyTtlSeconds();
        log.info("Redis connection store initialized: {}:{}", config.getHost(), config.getPort());
    }

    @Override
    public void recordConnection(String ipAddress) {
        if (ipAddress == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = Instant.now().getEpochSecond();

            jedis.incr(prefix + "total");
            jedis.expire(prefix + "total", ttlSeconds);

            String activeKey = prefix + "active:" + ipAddress;
            jedis.incr(activeKey);
            jedis.expire(activeKey, ttlSeconds);

            String historyKey = prefix + "history:" + ipAddress;
            jedis.zadd(historyKey, (double) now, now + ":" + System.nanoTime());
            jedis.expire(historyKey, ttlSeconds);
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis recordConnection failed: {}", e.getMessage());
        }
    }

    @Override
    public void recordDisconnection(String ipAddress) {
        if (ipAddress == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.decr(prefix + "total");

            String activeKey = prefix + "active:" + ipAddress;
            long val = jedis.decr(activeKey);
            if (val < 0) {
                jedis.set(activeKey, "0");
            }
            jedis.expire(activeKey, ttlSeconds);
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis recordDisconnection failed: {}", e.getMessage());
        }
    }

    @Override
    public int getActiveConnections(String ipAddress) {
        if (ipAddress == null) return 0;
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(prefix + "active:" + ipAddress);
            return val != null ? Math.max(0, Integer.parseInt(val)) : 0;
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis getActiveConnections failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getTotalActiveConnections() {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(prefix + "total");
            return val != null ? Math.max(0, Integer.parseInt(val)) : 0;
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis getTotalActiveConnections failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getRecentConnectionCount(String ipAddress, int windowSeconds) {
        if (ipAddress == null) return 0;
        try (Jedis jedis = jedisPool.getResource()) {
            long cutoff = Instant.now().getEpochSecond() - windowSeconds;
            String historyKey = prefix + "history:" + ipAddress;
            jedis.zremrangeByScore(historyKey, "-inf", String.valueOf(cutoff - 1));
            Long count = jedis.zcard(historyKey);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis getRecentConnectionCount failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void recordCommand(String ipAddress) {
        if (ipAddress == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = Instant.now().getEpochSecond();
            String cmdKey = prefix + "cmd:" + ipAddress;
            jedis.zadd(cmdKey, (double) now, now + ":" + System.nanoTime());
            jedis.expire(cmdKey, ttlSeconds);
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis recordCommand failed: {}", e.getMessage());
        }
    }

    @Override
    public int getCommandsPerMinute(String ipAddress) {
        if (ipAddress == null) return 0;
        try (Jedis jedis = jedisPool.getResource()) {
            long cutoff = Instant.now().getEpochSecond() - 60;
            String cmdKey = prefix + "cmd:" + ipAddress;
            jedis.zremrangeByScore(cmdKey, "-inf", String.valueOf(cutoff - 1));
            Long count = jedis.zcard(cmdKey);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis getCommandsPerMinute failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void recordBytesTransferred(String ipAddress, long bytes) {
        // Bytes-transferred tracking is local-only; not shared across cluster.
    }

    @Override
    public void reset() {
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = "0";
            ScanParams scanParams = new ScanParams().match(prefix + "*").count(100);
            do {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                cursor = result.getCursor();
                List<String> keys = result.getResult();
                if (!keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            SmtpMetrics.incrementDistributedStoreError();
            log.warn("Redis reset failed: {}", e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.debug("Redis connection store pool closed");
        }
    }
}
