package com.mimecast.robin.smtp.metrics;

import com.mimecast.robin.metrics.MetricsRegistry;
import io.micrometer.core.instrument.Counter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SMTP-related Micrometer metrics.
 *
 * <p>Provides counters for tracking email receipt operations including successful runs and exceptions.
 */
public final class SmtpMetrics {
    private static final Logger log = LogManager.getLogger(SmtpMetrics.class);

    private static volatile Counter emailReceiptStartCounter;
    private static volatile Counter emailReceiptSuccessCounter;
    private static volatile Counter emailReceiptLimitCounter;
    private static volatile Counter emailRblRejectionCounter;

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
            if (MetricsRegistry.getPrometheusRegistry() != null) {
                initializeCounters();
                log.info("SMTP metrics initialized");
            } else {
                log.warn("Cannot initialize SMTP metrics - Prometheus registry is null");
            }
        } catch (Exception e) {
            log.error("Failed to initialize SMTP metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Increment the email receipt start counter.
     * <p>Called when an email receipt connection is established and processing begins.
     */
    public static void incrementEmailReceiptStart() {
        try {
            if (emailReceiptStartCounter == null) {
                synchronized (SmtpMetrics.class) {
                    if (emailReceiptStartCounter == null) {
                        initializeCounters();
                    }
                }
            }
            if (emailReceiptStartCounter != null) {
                emailReceiptStartCounter.increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment email receipt start counter: {}", e.getMessage());
        }
    }

    /**
     * Increment the email receipt success counter.
     * <p>Called when an email receipt completes successfully.
     */
    public static void incrementEmailReceiptSuccess() {
        try {
            if (emailReceiptSuccessCounter == null) {
                synchronized (SmtpMetrics.class) {
                    if (emailReceiptSuccessCounter == null) {
                        initializeCounters();
                    }
                }
            }
            if (emailReceiptSuccessCounter != null) {
                emailReceiptSuccessCounter.increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment email receipt success counter: {}", e.getMessage());
        }
    }


    /**
     * Increment the email receipt exception counter.
     * <p>Called when an email receipt encounters an exception.
     *
     * @param exceptionType The simple name of the exception class.
     */
    public static void incrementEmailReceiptException(String exceptionType) {
        try {
            if (MetricsRegistry.getPrometheusRegistry() != null) {
                Counter.builder("smtp.email.receipt.exceptions")
                        .description("Number of exceptions during email receipt processing")
                        .tag("exception_type", exceptionType)
                        .register(MetricsRegistry.getPrometheusRegistry())
                        .increment();
            }

            if (MetricsRegistry.getGraphiteRegistry() != null) {
                Counter.builder("smtp.email.receipt.exceptions")
                        .description("Number of exceptions during email receipt processing")
                        .tag("exception_type", exceptionType)
                        .register(MetricsRegistry.getGraphiteRegistry())
                        .increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment email receipt exception counter: {}", e.getMessage());
        }
    }

    /**
     * Increment the email receipt limit counter.
     * <p>Called when an email receipt is terminated due to reaching error or transaction limits.
     */
    public static void incrementEmailReceiptLimit() {
        try {
            if (emailReceiptLimitCounter == null) {
                synchronized (SmtpMetrics.class) {
                    if (emailReceiptLimitCounter == null) {
                        initializeCounters();
                    }
                }
            }
            if (emailReceiptLimitCounter != null) {
                emailReceiptLimitCounter.increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment email receipt limit counter: {}", e.getMessage());
        }
    }

    /**
     * Increment the email RBL rejection counter.
     * <p>Called when an email connection is rejected due to RBL listing.
     */
    public static void incrementEmailRblRejection() {
        try {
            if (emailRblRejectionCounter == null) {
                synchronized (SmtpMetrics.class) {
                    if (emailRblRejectionCounter == null) {
                        initializeCounters();
                    }
                }
            }
            if (emailRblRejectionCounter != null) {
                emailRblRejectionCounter.increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment email RBL rejection counter: {}", e.getMessage());
        }
    }

    /**
     * Initialize the metric counters.
     * <p>This is called lazily on first use to ensure registries are available.
     */
    private static void initializeCounters() {
        if (MetricsRegistry.getPrometheusRegistry() != null) {
            emailReceiptStartCounter = Counter.builder("smtp.email.receipt.start")
                    .description("Number of email receipt connections started")
                    .register(MetricsRegistry.getPrometheusRegistry());

            emailReceiptSuccessCounter = Counter.builder("smtp.email.receipt.success")
                    .description("Number of successful email receipt operations")
                    .register(MetricsRegistry.getPrometheusRegistry());

            emailReceiptLimitCounter = Counter.builder("smtp.email.receipt.limit")
                    .description("Number of email receipt operations terminated due to error or transaction limits")
                    .register(MetricsRegistry.getPrometheusRegistry());

            emailRblRejectionCounter = Counter.builder("smtp.email.rbl.rejection")
                    .description("Number of connections rejected due to RBL listings")
                    .register(MetricsRegistry.getPrometheusRegistry());

            // Initialize exception counter with common exception types so it appears in metrics from the start
            Counter.builder("smtp.email.receipt.exceptions")
                    .description("Number of exceptions during email receipt processing")
                    .tag("exception_type", "IOException")
                    .register(MetricsRegistry.getPrometheusRegistry());

            Counter.builder("smtp.email.receipt.exceptions")
                    .description("Number of exceptions during email receipt processing")
                    .tag("exception_type", "SocketException")
                    .register(MetricsRegistry.getPrometheusRegistry());

            log.debug("Initialized SMTP metrics counters");
        }

        // Also register with Graphite if available
        if (MetricsRegistry.getGraphiteRegistry() != null) {
            Counter.builder("smtp.email.receipt.start")
                    .description("Number of email receipt connections started")
                    .register(MetricsRegistry.getGraphiteRegistry());

            Counter.builder("smtp.email.receipt.success")
                    .description("Number of successful email receipt operations")
                    .register(MetricsRegistry.getGraphiteRegistry());

            Counter.builder("smtp.email.receipt.limit")
                    .description("Number of email receipt operations terminated due to error or transaction limits")
                    .register(MetricsRegistry.getGraphiteRegistry());

            // Initialize exception counters for Graphite too
            Counter.builder("smtp.email.receipt.exceptions")
                    .description("Number of exceptions during email receipt processing")
                    .tag("exception_type", "IOException")
                    .register(MetricsRegistry.getGraphiteRegistry());

            Counter.builder("smtp.email.receipt.exceptions")
                    .description("Number of exceptions during email receipt processing")
                    .tag("exception_type", "SocketException")
                    .register(MetricsRegistry.getGraphiteRegistry());
        }
    }

    /**
     * Reset the counters (for testing purposes).
     */
    static void resetCounters() {
        emailReceiptStartCounter = null;
        emailReceiptSuccessCounter = null;
        emailReceiptLimitCounter = null;
    }
}
