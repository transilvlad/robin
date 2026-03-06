package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.DistributedRateConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RedisConnectionStore.
 *
 * <p>These tests require a running Redis instance on localhost:6379.
 * They are disabled by default and should be run manually in an environment
 * where Redis is available.
 */
@Disabled("Requires a running Redis instance on localhost:6379")
class RedisConnectionStoreTest {

    private DistributedRateConfig config() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("host", "localhost");
        map.put("port", 6379L);
        map.put("keyPrefix", "robin:test:");
        map.put("keyTtlSeconds", 60L);
        return new DistributedRateConfig(map);
    }

    @Test
    void testRecordAndGetActiveConnections() {
        try (RedisConnectionStore store = new RedisConnectionStore(config())) {
            store.reset();

            store.recordConnection("10.0.0.1");
            assertEquals(1, store.getActiveConnections("10.0.0.1"));
            assertEquals(1, store.getTotalActiveConnections());

            store.recordDisconnection("10.0.0.1");
            assertEquals(0, store.getActiveConnections("10.0.0.1"));
        }
    }

    @Test
    void testRecentConnectionCount() {
        try (RedisConnectionStore store = new RedisConnectionStore(config())) {
            store.reset();

            for (int i = 0; i < 5; i++) {
                store.recordConnection("10.0.0.2");
            }
            assertEquals(5, store.getRecentConnectionCount("10.0.0.2", 60));
        }
    }

    @Test
    void testCommandsPerMinute() {
        try (RedisConnectionStore store = new RedisConnectionStore(config())) {
            store.reset();

            for (int i = 0; i < 10; i++) {
                store.recordCommand("10.0.0.3");
            }
            assertTrue(store.getCommandsPerMinute("10.0.0.3") >= 10);
        }
    }

    @Test
    void testReset() {
        try (RedisConnectionStore store = new RedisConnectionStore(config())) {
            store.recordConnection("10.0.0.4");
            store.reset();
            assertEquals(0, store.getTotalActiveConnections());
            assertEquals(0, store.getActiveConnections("10.0.0.4"));
        }
    }

    // AutoCloseable helper so we can use try-with-resources in tests.
    private static class RedisConnectionStore extends com.mimecast.robin.smtp.security.RedisConnectionStore
            implements AutoCloseable {
        RedisConnectionStore(DistributedRateConfig config) {
            super(config);
        }

        @Override
        public void close() {
            shutdown();
        }
    }
}
