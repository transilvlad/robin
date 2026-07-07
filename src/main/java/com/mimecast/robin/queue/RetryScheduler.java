package com.mimecast.robin.queue;

import com.mimecast.robin.config.server.RetryConfig;
import com.mimecast.robin.main.Config;

/**
 * Retry scheduler utility class.
 * <p>Schedules retries using a geometric progression backoff strategy.
 * <p>The general idea is to space out retries to avoid overwhelming the system or remote servers.
 * This is particularly useful for email delivery where transient issues may resolve over time.
 * <p>The wait time before each retry is calculated using the formula:
 * <pre>
 *     wait_time = firstWaitMinutes * (growthFactor ^ retry_count)
 * </pre>
 * <p>Configuration is read from the queue configuration file (queue.json5) under the "retry" key.
 * Default values are:
 * <ul>
 *     <li>Total retries: 30</li>
 *     <li>Initial wait time: 1 minute</li>
 *     <li>Growth factor: 1.2</li>
 * </ul>
 * <p>Characteristics:
 * <ul>
 *     <li>Wait times increase geometrically</li>
 *     <li>Wait time for the first retry: 1 minute (default)</li>
 *     <li>Wait time for the last retry: 237 minutes (~4 hours with defaults)</li>
 *     <li>Total cumulative wait time if all retries are used: ~23.65 hours (with defaults)</li>
 *     <li>After reaching the maximum number of retries, the method returns -1 to indicate no further retries.</li>
 * </ul>
 * <p>Example wait times for the first few retries (with default configuration):
 * <ul>
 *     <li>Retry 1: 1.00 minutes</li>
 *     <li>Retry 2: 1.20 minutes</li>
 *     <li>Retry 3: 1.44 minutes</li>
 *     <li>Retry 4: 1.73 minutes</li>
 *     <li>Retry 5: 2.07 minutes</li>
 * </ul>
 * <p>Example usage:
 * <pre>
 *     int waitTime = RetryScheduler.getNextRetry(currentRetryCount);
 * </pre>
 *
 * @see RetryConfig
 */
public class RetryScheduler {

    /**
     * Private constructor to prevent instantiation.
     */
    private RetryScheduler() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Get the next retry wait time in seconds.
     *
     * @param retryCount Current retry count.
     * @return Wait time in seconds or -1 if no more retries.
     */
    public static int getNextRetry(int retryCount) {
        RetryConfig config = getRetryConfig();
        if (retryCount > config.getTotalRetries()) {
            return -1; // No more retries.
        }

        // Geometric progression backoff.
        return (int) Math.round(config.getFirstWaitMinutes() * Math.pow(config.getGrowthFactor(), retryCount)) * 60; // Return wait time in seconds.
    }

    /**
     * Gets the total number of retries from configuration.
     *
     * @return Total retries.
     */
    public static int getTotalRetries() {
        return getRetryConfig().getTotalRetries();
    }

    /**
     * Gets the initial wait time in minutes from configuration.
     *
     * @return First wait time in minutes.
     */
    public static int getFirstWaitMinutes() {
        return getRetryConfig().getFirstWaitMinutes();
    }

    /**
     * Gets the growth factor from configuration.
     *
     * @return Growth factor.
     */
    public static double getGrowthFactor() {
        return getRetryConfig().getGrowthFactor();
    }

    /**
     * Gets the retry configuration from server config.
     *
     * @return RetryConfig instance.
     */
    private static RetryConfig getRetryConfig() {
        var queueConfig = Config.getServer().getQueue();
        if (queueConfig.getMap().containsKey("retry")) {
            return new RetryConfig(queueConfig.getMapProperty("retry"));
        }
        // Return config with all defaults if "retry" section doesn't exist.
        return new RetryConfig(new java.util.HashMap<>());
    }
}
