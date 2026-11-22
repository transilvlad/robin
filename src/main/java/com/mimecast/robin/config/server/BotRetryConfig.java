package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Retry configuration for report bot endpoint delivery.
 *
 * <p>Report bots ({@code dmarc}, {@code tlsrpt} and {@code forensic}) keep a report on disk when its
 * endpoint is unavailable and send it again later. The backoff uses the same geometric progression as
 * the relay queue (see {@link com.mimecast.robin.queue.RetryScheduler}), with defaults sized for an
 * endpoint restart or a short outage rather than remote mail delivery.
 *
 * <p>Read from the optional {@code retry} object next to the {@code bots} list in {@code bots.json5}.
 *
 * @see RetryConfig
 */
public class BotRetryConfig extends BasicConfig {

    /**
     * Default total number of retries.
     * <p>With the other defaults the waits reach the 30 minute cap by the sixth retry, so 52 retries
     * last about 24 hours and end close to {@link #DEFAULT_MAX_AGE_SECONDS}.
     */
    public static final long DEFAULT_TOTAL_RETRIES = 52L;

    /**
     * Default initial wait time in minutes.
     */
    public static final long DEFAULT_FIRST_WAIT_MINUTES = 1L;

    /**
     * Default growth factor for geometric progression.
     */
    public static final double DEFAULT_GROWTH_FACTOR = 2.0;

    /**
     * Default longest wait between retries in seconds.
     */
    public static final long DEFAULT_MAX_WAIT_SECONDS = 1800L;

    /**
     * Default jitter fraction applied to each wait.
     */
    public static final double DEFAULT_JITTER_FRACTION = 0.1;

    /**
     * Default age in seconds after which a report is given up.
     */
    public static final long DEFAULT_MAX_AGE_SECONDS = 86400L;

    /**
     * Default number of reports allowed to wait on disk.
     */
    public static final long DEFAULT_MAX_PENDING = 10000L;

    /**
     * Default interval in seconds between checks for due reports.
     */
    public static final long DEFAULT_POLL_SECONDS = 30L;

    /**
     * Constructs a new BotRetryConfig instance with given map.
     *
     * @param map Configuration map, may be null.
     */
    public BotRetryConfig(Map<String, Object> map) {
        super(map != null ? map : new HashMap<>());
        if (getMaxPending() < 0 || getPollSeconds() <= 0) {
            throw new IllegalArgumentException("Invalid bot retry configuration");
        }
        // Validates the backoff values.
        getRetryConfig();
    }

    /**
     * Whether failed report deliveries are kept and retried.
     *
     * @return True if enabled.
     */
    public boolean isEnabled() {
        return getBooleanProperty("enabled", true);
    }

    /**
     * Gets the number of reports allowed to wait on disk.
     * <p>When the limit is reached further failed reports are logged and dropped.
     *
     * @return Maximum pending reports.
     */
    public int getMaxPending() {
        return Math.toIntExact(getLongProperty("maxPending", DEFAULT_MAX_PENDING));
    }

    /**
     * Gets the interval in seconds between checks for due reports.
     *
     * @return Poll interval in seconds.
     */
    public long getPollSeconds() {
        return getLongProperty("pollSeconds", DEFAULT_POLL_SECONDS);
    }

    /**
     * Gets the backoff configuration with the bot defaults applied to unset values.
     *
     * @return RetryConfig instance.
     */
    public RetryConfig getRetryConfig() {
        Map<String, Object> retry = new HashMap<>();
        retry.put("totalRetries", getLongProperty("totalRetries", DEFAULT_TOTAL_RETRIES));
        retry.put("firstWaitMinutes", getLongProperty("firstWaitMinutes", DEFAULT_FIRST_WAIT_MINUTES));
        retry.put("growthFactor", getDoubleProperty("growthFactor", DEFAULT_GROWTH_FACTOR));
        retry.put("maxWaitSeconds", getLongProperty("maxWaitSeconds", DEFAULT_MAX_WAIT_SECONDS));
        retry.put("jitterFraction", getDoubleProperty("jitterFraction", DEFAULT_JITTER_FRACTION));
        retry.put("maxAgeSeconds", getLongProperty("maxAgeSeconds", DEFAULT_MAX_AGE_SECONDS));
        return new RetryConfig(retry);
    }
}
