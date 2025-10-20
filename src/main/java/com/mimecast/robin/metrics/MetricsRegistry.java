package com.mimecast.robin.metrics;

import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Global access to metric registries for background jobs like MetricsCron.
 */
public final class MetricsRegistry {
    private static volatile PrometheusMeterRegistry prometheusRegistry;
    private static volatile GraphiteMeterRegistry graphiteRegistry;

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
}
