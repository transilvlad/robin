package com.mimecast.robin.queue;

import com.mimecast.robin.config.server.RetryConfig;
import com.mimecast.robin.main.Config;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry scheduler utility class.
 * <p>Schedules retries using a geometric progression backoff strategy.
 * <p>The general idea is to space out retries to avoid overwhelming the system or remote servers.
 * This is particularly useful for email delivery where transient issues may resolve over time.
 * <p>The wait time before each retry is calculated using the formula:
 * <pre>
 *     wait_seconds = firstWaitMinutes * 60 * (growthFactor ^ (retry_count - 1))
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
 *     <li>Waits are bounded by maxWaitSeconds, including configured jitter.</li>
 *     <li>Queue age is independently bounded when maxAgeSeconds is enabled.</li>
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
        return calculateDelay(getRetryConfig(), retryCount, ThreadLocalRandom.current().nextDouble());
    }

    static int calculateDelay(RetryConfig config, int retryCount, double random) {
        double growth = config.getGrowthFactor();
        double jitter = config.getJitterFraction();
        if (random < 0 || random > 1 || !Double.isFinite(random)) {
            throw new IllegalArgumentException("Invalid retry jitter sample");
        }
        if (retryCount > config.getTotalRetries()) {
            return -1;
        }
        double seconds = Math.min(config.getMaxWaitSeconds(),
                config.getFirstWaitMinutes() * 60.0 * Math.pow(growth, Math.max(0L, (long) retryCount - 1)));
        seconds *= 1.0 - jitter + 2.0 * jitter * random;
        return (int) Math.max(1L, Math.round(Math.min(config.getMaxWaitSeconds(), seconds)));
    }

    public static long getMaxAgeSeconds() {
        return getRetryConfig().getMaxAgeSeconds();
    }

    public static void validateConfiguration() {
        getRetryConfig();
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
