package com.mimecast.robin.queue;

import com.mimecast.robin.config.server.RetryConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrySchedulerTest {

    @Test
    void testFirstRetry() {
        int wait = RetryScheduler.getNextRetry(1);
        assertEquals(60, wait, "First retry should be 60 seconds");
    }

    @Test
    void testLastRetry() {
        int wait = RetryScheduler.getNextRetry(30);
        assertEquals(11869, wait, "Wait must retain second precision without a one-retry exponent shift");
    }

    @Test
    void testIncreasingWaitTimes() {
        int prev = RetryScheduler.getNextRetry(1);
        for (int i = 2; i < 10; i++) {
            int current = RetryScheduler.getNextRetry(i);
            assertTrue(current >= prev, "Wait time should not decrease");
            prev = current;
        }
    }

    @Test
    void testNoMoreRetriesAfterLimit() {
        assertEquals(-1, RetryScheduler.getNextRetry(31), "Should stop after 30 retries");
        assertEquals(-1, RetryScheduler.getNextRetry(50), "Should stop after 30 retries");
    }

    @Test
    void testZeroOrNegativeRetryCount() {
        // Defensive: negative values or 0 should behave like 1.
        assertEquals(60, RetryScheduler.getNextRetry(0), "Retry count 0 should return 60 seconds");
        assertEquals(60, RetryScheduler.getNextRetry(-1), "Negative retry count should default to first wait");
        assertEquals(60, RetryScheduler.getNextRetry(Integer.MIN_VALUE));
    }

    @Test
    void capsAfterJitterWithoutIntegerOverflowAndHasDeterministicBounds() {
        RetryConfig config = new RetryConfig(Map.of("firstWaitMinutes", 1L, "growthFactor", 2.0,
                "maxWaitSeconds", 3600L, "jitterFraction", 0.2, "totalRetries", 10000L));
        assertEquals(48, RetryScheduler.calculateDelay(config, 1, 0));
        assertEquals(72, RetryScheduler.calculateDelay(config, 1, 1));
        assertEquals(2880, RetryScheduler.calculateDelay(config, 10000, 0));
        assertEquals(3600, RetryScheduler.calculateDelay(config, 10000, 1));
    }

    @Test
    void rejectsInvalidBackoffRatherThanSchedulingImmediateRetryLoops() {
        for (Map<String, Object> values : java.util.List.<Map<String, Object>>of(
                Map.of("firstWaitMinutes", 0L), Map.of("growthFactor", Double.NaN),
                Map.of("maxWaitSeconds", 0L), Map.of("jitterFraction", 1.0), Map.of("maxAgeSeconds", -1L))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> RetryScheduler.calculateDelay(new RetryConfig(values), 1, 0.5));
        }
    }
}
