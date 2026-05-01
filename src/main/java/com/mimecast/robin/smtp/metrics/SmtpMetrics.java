package com.mimecast.robin.smtp.metrics;

import com.mimecast.robin.metrics.MetricsRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * SMTP-related Micrometer metrics.
 *
 * <p>Provides counters for tracking email receipt operations including successful runs and exceptions.
 * <p>Uses a composite registry to write metrics to both Prometheus and Graphite simultaneously.
 */
public final class SmtpMetrics {
    private static final Logger log = LogManager.getLogger(SmtpMetrics.class);

    private static volatile Counter emailReceiptStartCounter;
    private static volatile Counter emailReceiptSuccessCounter;
    private static volatile Counter emailReceiptLimitCounter;
    private static volatile Counter emailRblRejectionCounter;
    private static volatile Counter emailVirusRejectionCounter;
    private static volatile Counter emailSpamRejectionCounter;
    private static volatile Counter dosRateLimitRejectionCounter;
    private static volatile Counter dosConnectionLimitRejectionCounter;
    private static volatile Counter dosTarpitCounter;
    private static volatile Counter dosSlowTransferRejectionCounter;
    private static volatile Counter dosCommandFloodRejectionCounter;
    private static volatile Counter whitelistBypassCounter;
    private static volatile Counter adaptiveRateLimitAppliedCounter;
    private static volatile Counter geoIpBlockRejectionCounter;
    private static volatile Counter geoIpLimitAppliedCounter;
    private static volatile Counter distributedStoreErrorCounter;

    /**
     * Private constructor for utility class.
     */
    private SmtpMetrics() {
    }

    /**
     * Initialize all metrics with zero values.
     * <p>This should be called during application startup to ensure metrics appear in endpoints
     * even before any SMTP traffic is processed.
     */
    public static void initialize() {
        try {
            if (emailReceiptStartCounter == null) {
                initializeCounters();
                log.info("SMTP metrics initialized");
            }
        } catch (Exception e) {
            log.error("Failed to initialize SMTP metrics: {}", e.getMessage());
        }
    }

    /**
     * Increment the email receipt start counter.
     * <p>Called when an email receipt connection is established and processing begins.
     */
    public static void incrementEmailReceiptStart() {
        incrementCounter(() -> emailReceiptStartCounter, "email receipt start counter");
    }

    /**
     * Increment the email receipt success counter.
     * <p>Called when an email receipt completes successfully.
     */
    public static void incrementEmailReceiptSuccess() {
        incrementCounter(() -> emailReceiptSuccessCounter, "email receipt success counter");
    }

    /**
     * Increment the email receipt limit counter.
     * <p>Called when an email receipt is terminated due to reaching error or transaction limits.
     */
    public static void incrementEmailReceiptLimit() {
        incrementCounter(() -> emailReceiptLimitCounter, "email receipt limit counter");
    }

    /**
     * Increment the email RBL rejection counter.
     * <p>Called when an email connection is rejected due to RBL listing.
     */
    public static void incrementEmailRblRejection() {
        incrementCounter(() -> emailRblRejectionCounter, "email RBL rejection counter");
    }

    /**
     * Increment the email virus rejection counter.
     * <p>Called when an email is rejected due to virus detection.
     */
    public static void incrementEmailVirusRejection() {
        incrementCounter(() -> emailVirusRejectionCounter, "email virus rejection counter");
    }

    /**
     * Increment the email spam rejection counter.
     * <p>Called when an email is rejected due to spam or phishing detection.
     */
    public static void incrementEmailSpamRejection() {
        incrementCounter(() -> emailSpamRejectionCounter, "email spam rejection counter");
    }

    /**
     * Increment the DoS rate limit rejection counter.
     * <p>Called when a connection is rejected due to rate limiting.
     */
    public static void incrementDosRateLimitRejection() {
        incrementCounter(() -> dosRateLimitRejectionCounter, "DoS rate limit rejection counter");
    }

    /**
     * Increment the DoS connection limit rejection counter.
     * <p>Called when a connection is rejected due to connection limits.
     */
    public static void incrementDosConnectionLimitRejection() {
        incrementCounter(() -> dosConnectionLimitRejectionCounter, "DoS connection limit rejection counter");
    }

    /**
     * Increment the DoS tarpit counter.
     * <p>Called when a connection is tarpitted due to suspicious behavior.
     */
    public static void incrementDosTarpit() {
        incrementCounter(() -> dosTarpitCounter, "DoS tarpit counter");
    }

    /**
     * Increment the DoS slow transfer rejection counter.
     * <p>Called when a connection is rejected due to slow data transfer (slowloris attack).
     */
    public static void incrementDosSlowTransferRejection() {
        incrementCounter(() -> dosSlowTransferRejectionCounter, "DoS slow transfer rejection counter");
    }

    /**
     * Increment the DoS command flood rejection counter.
     * <p>Called when a connection is rejected due to command flooding.
     */
    public static void incrementDosCommandFloodRejection() {
        incrementCounter(() -> dosCommandFloodRejectionCounter, "DoS command flood rejection counter");
    }

    /**
     * Increment the whitelist bypass counter.
     * <p>Called when a connection is accepted from a whitelisted IP, bypassing DoS limits and RBL checks.
     */
    public static void incrementWhitelistBypass() {
        incrementCounter(() -> whitelistBypassCounter, "whitelist bypass counter");
    }

    /**
     * Increment the adaptive rate limit applied counter.
     * <p>Called when adaptive rate limiting reduces connection limits due to high server load.
     */
    public static void incrementAdaptiveRateLimitApplied() {
        incrementCounter(() -> adaptiveRateLimitAppliedCounter, "adaptive rate limit applied counter");
    }

    /**
     * Increment the GeoIP block rejection counter.
     * <p>Called when a connection is rejected due to GeoIP country block policy.
     */
    public static void incrementGeoIpBlockRejection() {
        incrementCounter(() -> geoIpBlockRejectionCounter, "GeoIP block rejection counter");
    }

    /**
     * Increment the GeoIP limit applied counter.
     * <p>Called when a connection from a GeoIP-limited country has reduced limits applied.
     */
    public static void incrementGeoIpLimitApplied() {
        incrementCounter(() -> geoIpLimitAppliedCounter, "GeoIP limit applied counter");
    }

    /**
     * Increment the distributed store error counter.
     * <p>Called when a Redis operation in {@link com.mimecast.robin.smtp.security.RedisConnectionStore} fails.
     */
    public static void incrementDistributedStoreError() {
        incrementCounter(() -> distributedStoreErrorCounter, "distributed store error counter");
    }

    /**
     * Increment the email receipt exception counter.
     * <p>Called when an email receipt encounters an exception.
     *
     * @param exceptionType The simple name of the exception class.
     */
    public static void incrementEmailReceiptException(String exceptionType) {
        try {
            MeterRegistry registry = MetricsRegistry.getCompositeRegistry();
            if (registry == null) {
                return;
            }

            try {
                Counter.builder("robin.email.receipt.exception")
                        .description("Number of exceptions during email receipt processing")
                        .tag("exception_type", exceptionType)
                        .register(registry)
                        .increment();
            } catch (IllegalArgumentException e) {
                // Counter with this tag already exists, find and increment it.
                Counter counter = registry
                        .find("robin.email.receipt.exception")
                        .tag("exception_type", exceptionType)
                        .counter();
                if (counter != null) {
                    counter.increment();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to increment email receipt exception counter: {}", e.getMessage());
        }
    }

    /**
     * Generic helper to increment a counter with lazy initialization.
     * <p>Handles double-checked locking for thread-safe lazy initialization and exception handling.
     *
     * @param counterSupplier Supplier that returns the counter field
     * @param counterName     Name of the counter for logging purposes
     */
    private static void incrementCounter(Supplier<Counter> counterSupplier, String counterName) {
        try {
            Counter counter = counterSupplier.get();
            if (counter == null) {
                synchronized (SmtpMetrics.class) {
                    counter = counterSupplier.get();
                    if (counter == null) {
                        initializeCounters();
                        counter = counterSupplier.get();
                    }
                }
            }
            if (counter != null) {
                counter.increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment {}: {}", counterName, e.getMessage());
        }
    }

    /**
     * Initialize the metric counters.
     * <p>This is called lazily on first use to ensure registries are available.
     * <p>Uses composite registry to write to both Prometheus and Graphite simultaneously.
     */
    private static void initializeCounters() {
        MeterRegistry registry = MetricsRegistry.getCompositeRegistry();
        if (registry == null) {
            log.warn("No metric registries configured, SMTP metrics will not be recorded");
            return;
        }

        emailReceiptStartCounter = Counter.builder("robin.email.receipt.start")
                .description("Number of email receipt connections started")
                .register(registry);

        emailReceiptSuccessCounter = Counter.builder("robin.email.receipt.success")
                .description("Number of successful email receipt operations")
                .register(registry);

        emailReceiptLimitCounter = Counter.builder("robin.email.receipt.limit")
                .description("Number of email receipt operations terminated due to limits")
                .register(registry);

        emailRblRejectionCounter = Counter.builder("robin.email.rbl.rejection")
                .description("Number of connections rejected due to RBL listings")
                .register(registry);

        emailVirusRejectionCounter = Counter.builder("robin.email.virus.rejection")
                .description("Number of emails rejected due to virus detection")
                .register(registry);

        emailSpamRejectionCounter = Counter.builder("robin.email.spam.rejection")
                .description("Number of emails rejected due to spam or phishing detection")
                .register(registry);

        dosRateLimitRejectionCounter = Counter.builder("robin.dos.ratelimit.rejection")
                .description("Number of connections rejected due to rate limiting")
                .register(registry);

        dosConnectionLimitRejectionCounter = Counter.builder("robin.dos.connectionlimit.rejection")
                .description("Number of connections rejected due to connection limits")
                .register(registry);

        dosTarpitCounter = Counter.builder("robin.dos.tarpit")
                .description("Number of connections tarpitted due to suspicious behavior")
                .register(registry);

        dosSlowTransferRejectionCounter = Counter.builder("robin.dos.slowtransfer.rejection")
                .description("Number of connections rejected due to slow data transfer")
                .register(registry);

        dosCommandFloodRejectionCounter = Counter.builder("robin.dos.commandflood.rejection")
                .description("Number of connections rejected due to command flooding")
                .register(registry);

        whitelistBypassCounter = Counter.builder("robin.whitelist.bypass")
                .description("Number of connections from whitelisted IPs bypassing DoS limits and RBL")
                .register(registry);

        adaptiveRateLimitAppliedCounter = Counter.builder("robin.adaptive.ratelimit.applied")
                .description("Number of times adaptive rate limiting reduced connection limits")
                .register(registry);

        geoIpBlockRejectionCounter = Counter.builder("robin.geoip.block.rejection")
                .description("Number of connections rejected due to GeoIP country block policy")
                .register(registry);

        geoIpLimitAppliedCounter = Counter.builder("robin.geoip.limit.applied")
                .description("Number of connections with reduced limits due to GeoIP country limit policy")
                .register(registry);

        distributedStoreErrorCounter = Counter.builder("robin.distributed.store.error")
                .description("Number of errors encountered by the Redis connection store")
                .register(registry);

        Counter.builder("robin.email.receipt.exception")
                .description("Number of exceptions during email receipt processing")
                .tag("exception_type", "Exception")
                .register(registry);

        log.debug("Initialized SMTP metrics counters");
    }

    /**
     * Reset the counters (for testing purposes).
     */
    static void resetCounters() {
        emailReceiptStartCounter = null;
        emailReceiptSuccessCounter = null;
        emailReceiptLimitCounter = null;
        emailRblRejectionCounter = null;
        emailVirusRejectionCounter = null;
        emailSpamRejectionCounter = null;
        dosRateLimitRejectionCounter = null;
        dosConnectionLimitRejectionCounter = null;
        dosTarpitCounter = null;
        dosSlowTransferRejectionCounter = null;
        dosCommandFloodRejectionCounter = null;
        whitelistBypassCounter = null;
        adaptiveRateLimitAppliedCounter = null;
        geoIpBlockRejectionCounter = null;
        geoIpLimitAppliedCounter = null;
        distributedStoreErrorCounter = null;
    }
}
