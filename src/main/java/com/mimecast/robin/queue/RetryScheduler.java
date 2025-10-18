package com.mimecast.robin.queue;

/**
 * Retry scheduler utility class.
 * <p>Schedules retries using a geometric progression backoff strategy.
 * <p>The general idea is to space out retries to avoid overwhelming the system or remote servers.
 * This is particularly useful email delivery where transient issues may resolve over time.
 * <p> The wait time before each retry is calculated using the formula:
 * <pre>
 *     wait_time = FIRST_WAIT_MINUTES * (GROWTH_FACTOR ^ retry_count)
 * </pre>
 * <p> Configuration:
 * <ul>
 *     <li>Total retries: 30</li>
 *     <li>Initial wait time: 1 minute</li>
 *     <li>Growth factor: 1.2</li>
 * </ul>
 * <p> Characteristics:
 * <ul>
 *     <li>Wait times increase geometrically</li>
 *     <li>Wait time for the first retry: 1 minute</li>
 *     <li>Wait time for the last retry: 237 minutes (~4 hours)</li>
 *     <li>Total cumulative wait time if all retries are used: ~23.65 hours</li>
 *     <li>After reaching the maximum number of retries, the method returns -1 to indicate no further retries.</li>
 * </ul>
 * <p> Example wait times for the first few retries:
 * <ul>
 *     <li>Retry 1: 1.00 minutes</li>
 *     <li>Retry 2: 1.20 minutes</li>
 *     <li>Retry 3: 1.44 minutes</li>
 *     <li>Retry 4: 1.73 minutes</li>
 *     <li>Retry 5: 2.07 minutes</li>
 * </ul>
 * <p> Example usage:
 * <pre>
 *     int waitTime = RetryScheduler.getNextRetry(currentRetryCount);
 * </pre>
 */
public class RetryScheduler {

    private static final int TOTAL_RETRIES = 30;
    private static final int FIRST_WAIT_MINUTES = 1; // Initial wait time in minutes for retry 1.
    private static final double GROWTH_FACTOR = 1.2;   // Geometric progression factor.

    /**
     * Get the next retry wait time in seconds.
     *
     * @param retryCount Current retry count.
     * @return Wait time in seconds or -1 if no more retries.
     */
    public static int getNextRetry(int retryCount) {
        if (retryCount > TOTAL_RETRIES) {
            return -1; // No more retries.
        }

        // Geometric progression backoff.
        return (int) Math.round(FIRST_WAIT_MINUTES * Math.pow(GROWTH_FACTOR, retryCount)) * 60; // Return wait time in seconds.
    }

    // Added getters to expose scheduler configuration for metrics/health.
    public static int getTotalRetries() {
        return TOTAL_RETRIES;
    }

    public static int getFirstWaitMinutes() {
        return FIRST_WAIT_MINUTES;
    }

    public static double getGrowthFactor() {
        return GROWTH_FACTOR;
    }
}
