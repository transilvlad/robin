package com.mimecast.robin.queue;

import org.junit.jupiter.api.Test;

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
        assertEquals(14220, wait, "Last retry should be under 1 day");
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
        assertEquals(60, RetryScheduler.getNextRetry(-1), "Retry count 0 should return 60 seconds");
        assertEquals(60, RetryScheduler.getNextRetry(-1), "Negative retry count should default to first wait");
    }
}
