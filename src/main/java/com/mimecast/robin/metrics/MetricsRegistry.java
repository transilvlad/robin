package com.mimecast.robin.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Global access to metric registries for background jobs like MetricsCron.
 *
 * <p>Provides both individual registries (for endpoint-specific scraping) and a composite
 * registry that writes to both simultaneously (for counter increments).
 */
public final class MetricsRegistry {
    private static volatile PrometheusMeterRegistry prometheusRegistry;
    private static volatile GraphiteMeterRegistry graphiteRegistry;
    private static volatile CompositeMeterRegistry compositeRegistry;

    /**
     * Private constructor for utility class.
     */
    private MetricsRegistry() {
    }

    /**
     * Register the metric registries.
     *
     * @param prom     Prometheus registry.
     * @param graphite Graphite registry.
     */
    public static void register(PrometheusMeterRegistry prom, GraphiteMeterRegistry graphite) {
        prometheusRegistry = prom;
        graphiteRegistry = graphite;

        // Create composite registry that writes to both
        compositeRegistry = new CompositeMeterRegistry();
        if (prom != null) {
            compositeRegistry.add(prom);
        }
        if (graphite != null) {
            compositeRegistry.add(graphite);
        }
    }

    /**
     * Get the Prometheus registry.
     *
     * @return Prometheus registry.
     */
    public static PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }

    /**
     * Get the Graphite registry.
     *
     * @return Graphite registry.
     */
    public static GraphiteMeterRegistry getGraphiteRegistry() {
        return graphiteRegistry;
    }

    /**
     * Get the composite registry that writes to all configured registries.
     * <p>Use this for registering counters that should appear in both Prometheus and Graphite.
     *
     * @return Composite registry, or null if no registries are configured.
     */
    public static MeterRegistry getCompositeRegistry() {
        return compositeRegistry;
    }
}
