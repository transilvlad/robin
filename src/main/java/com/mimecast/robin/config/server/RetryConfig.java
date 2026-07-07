package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

/**
 * Retry scheduler configuration.
 *
 * <p>This class provides type-safe access to retry backoff configuration settings
 * used by the {@link com.mimecast.robin.queue.RetryScheduler} for determining
 * wait times between message relay attempts.
 *
 * @see com.mimecast.robin.queue.RetryScheduler
 */
public class RetryConfig extends BasicConfig {

    /**
     * Default total number of retries.
     */
    public static final int DEFAULT_TOTAL_RETRIES = 30;

    /**
     * Default initial wait time in minutes.
     */
    public static final int DEFAULT_FIRST_WAIT_MINUTES = 1;

    /**
     * Default growth factor for geometric progression.
     */
    public static final double DEFAULT_GROWTH_FACTOR = 1.2;

    /**
     * Constructs a new RetryConfig instance with given map.
     *
     * @param map Configuration map.
     */
    public RetryConfig(java.util.Map map) {
        super(map);
    }

    /**
     * Gets the total number of retries before giving up.
     *
     * @return Total retries.
     */
    public int getTotalRetries() {
        return Math.toIntExact(getLongProperty("totalRetries", (long) DEFAULT_TOTAL_RETRIES));
    }

    /**
     * Gets the initial wait time in minutes for the first retry.
     *
     * @return First wait time in minutes.
     */
    public int getFirstWaitMinutes() {
        return Math.toIntExact(getLongProperty("firstWaitMinutes", (long) DEFAULT_FIRST_WAIT_MINUTES));
    }

    /**
     * Gets the growth factor for geometric progression backoff.
     *
     * <p>Each retry wait time is calculated as:
     * {@code wait_time = firstWaitMinutes * (growthFactor ^ retryCount)}
     *
     * @return Growth factor.
     */
    public double getGrowthFactor() {
        return getDoubleProperty("growthFactor", DEFAULT_GROWTH_FACTOR);
    }
}
