package com.mimecast.robin.config.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mimecast.robin.queue.RetryScheduler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BotRetryConfigTest {

    @Test
    void testDefaultsWhenRetryObjectIsAbsent() {
        BotRetryConfig config = new BotConfig(new HashMap<>(Map.of("bots", List.of()))).getRetry();

        assertTrue(config.isEnabled());
        assertEquals(10000, config.getMaxPending());
        assertEquals(30, config.getPollSeconds());
        RetryConfig retry = config.getRetryConfig();
        assertEquals(52, retry.getTotalRetries());
        assertEquals(1, retry.getFirstWaitMinutes());
        assertEquals(2.0, retry.getGrowthFactor());
        assertEquals(1800, retry.getMaxWaitSeconds());
        assertEquals(0.1, retry.getJitterFraction());
        assertEquals(86400, retry.getMaxAgeSeconds());
        assertTrue(new BotConfig(null).getRetry().isEnabled());
    }

    @Test
    void testRetryObjectOverridesDefaults() {
        Map<String, Object> retry = new HashMap<>();
        retry.put("enabled", false);
        retry.put("totalRetries", 3L);
        retry.put("maxPending", 50L);
        BotRetryConfig config = new BotConfig(new HashMap<>(Map.of("retry", retry))).getRetry();

        assertFalse(config.isEnabled());
        assertEquals(50, config.getMaxPending());
        assertEquals(3, config.getRetryConfig().getTotalRetries());
        assertEquals(2.0, config.getRetryConfig().getGrowthFactor());
    }

    @Test
    void testExampleConfigMatchesDefaults() throws Exception {
        Map<String, Object> values = new Gson().fromJson(Files.readString(Path.of("cfg/bots.json5")),
                new TypeToken<Map<String, Object>>() {}.getType());
        BotRetryConfig example = new BotConfig(values).getRetry();
        BotRetryConfig defaults = new BotRetryConfig(null);

        assertEquals(defaults.isEnabled(), example.isEnabled());
        assertEquals(defaults.getMaxPending(), example.getMaxPending());
        assertEquals(defaults.getPollSeconds(), example.getPollSeconds());
        RetryConfig exampleRetry = example.getRetryConfig();
        RetryConfig defaultRetry = defaults.getRetryConfig();
        assertEquals(defaultRetry.getTotalRetries(), exampleRetry.getTotalRetries());
        assertEquals(defaultRetry.getFirstWaitMinutes(), exampleRetry.getFirstWaitMinutes());
        assertEquals(defaultRetry.getGrowthFactor(), exampleRetry.getGrowthFactor());
        assertEquals(defaultRetry.getMaxWaitSeconds(), exampleRetry.getMaxWaitSeconds());
        assertEquals(defaultRetry.getJitterFraction(), exampleRetry.getJitterFraction());
        assertEquals(defaultRetry.getMaxAgeSeconds(), exampleRetry.getMaxAgeSeconds());
    }

    @Test
    void testInvalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BotRetryConfig(Map.of("growthFactor", 0.5)));
        assertThrows(IllegalArgumentException.class, () -> new BotRetryConfig(Map.of("pollSeconds", 0L)));
        assertThrows(IllegalArgumentException.class, () -> new BotRetryConfig(Map.of("maxPending", -1L)));
    }

    @Test
    void testDefaultBackoffRetriesForAboutADay() {
        Map<String, Object> noJitter = new HashMap<>();
        noJitter.put("jitterFraction", 0.0);
        RetryConfig retry = new BotRetryConfig(noJitter).getRetryConfig();

        assertEquals(60, RetryScheduler.getNextRetry(retry, 1));
        assertEquals(120, RetryScheduler.getNextRetry(retry, 2));
        assertEquals(1800, RetryScheduler.getNextRetry(retry, 6));
        assertEquals(-1, RetryScheduler.getNextRetry(retry, 53));

        long total = 0;
        for (int i = 1; i <= retry.getTotalRetries(); i++) {
            total += RetryScheduler.getNextRetry(retry, i);
        }
        assertTrue(total >= retry.getMaxAgeSeconds() - 1800 && total <= retry.getMaxAgeSeconds() + 1800,
                "Retries should end close to the age limit, was " + total);
    }
}
