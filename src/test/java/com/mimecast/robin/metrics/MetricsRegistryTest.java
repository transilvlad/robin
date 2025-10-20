package com.mimecast.robin.metrics;

import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsRegistry.
 */
class MetricsRegistryTest {

    @BeforeEach
    void setUp() {
        // Clear registries before each test.
        MetricsRegistry.register(null, null);
    }

    @AfterEach
    void tearDown() {
        // Clear registries after each test.
        MetricsRegistry.register(null, null);
    }

    @Test
    void testRegisterPrometheusRegistry() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        MetricsRegistry.register(prometheusRegistry, null);

        assertNotNull(MetricsRegistry.getPrometheusRegistry());
        assertSame(prometheusRegistry, MetricsRegistry.getPrometheusRegistry());
    }

    @Test
    void testRegisterGraphiteRegistry() {
        GraphiteMeterRegistry graphiteRegistry = new GraphiteMeterRegistry(GraphiteConfig.DEFAULT, io.micrometer.core.instrument.Clock.SYSTEM);

        MetricsRegistry.register(null, graphiteRegistry);

        assertNotNull(MetricsRegistry.getGraphiteRegistry());
        assertSame(graphiteRegistry, MetricsRegistry.getGraphiteRegistry());
    }

    @Test
    void testRegisterBothRegistries() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        GraphiteMeterRegistry graphiteRegistry = new GraphiteMeterRegistry(GraphiteConfig.DEFAULT, io.micrometer.core.instrument.Clock.SYSTEM);

        MetricsRegistry.register(prometheusRegistry, graphiteRegistry);

        assertNotNull(MetricsRegistry.getPrometheusRegistry());
        assertNotNull(MetricsRegistry.getGraphiteRegistry());
        assertSame(prometheusRegistry, MetricsRegistry.getPrometheusRegistry());
        assertSame(graphiteRegistry, MetricsRegistry.getGraphiteRegistry());
    }

    @Test
    void testGetPrometheusRegistryWhenNull() {
        MetricsRegistry.register(null, null);

        assertNull(MetricsRegistry.getPrometheusRegistry());
    }

    @Test
    void testGetGraphiteRegistryWhenNull() {
        MetricsRegistry.register(null, null);

        assertNull(MetricsRegistry.getGraphiteRegistry());
    }

    @Test
    void testRegisterOverwritesPreviousRegistries() {
        PrometheusMeterRegistry prometheusRegistry1 = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMeterRegistry prometheusRegistry2 = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        MetricsRegistry.register(prometheusRegistry1, null);
        assertSame(prometheusRegistry1, MetricsRegistry.getPrometheusRegistry());

        MetricsRegistry.register(prometheusRegistry2, null);
        assertSame(prometheusRegistry2, MetricsRegistry.getPrometheusRegistry());
        assertNotSame(prometheusRegistry1, MetricsRegistry.getPrometheusRegistry());
    }

    @Test
    void testVolatileAccessibility() {
        // Test that multiple threads can access the registries.
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        GraphiteMeterRegistry graphiteRegistry = new GraphiteMeterRegistry(GraphiteConfig.DEFAULT, io.micrometer.core.instrument.Clock.SYSTEM);

        MetricsRegistry.register(prometheusRegistry, graphiteRegistry);

        // Simulate concurrent access.
        Thread t1 = new Thread(() -> {
            assertNotNull(MetricsRegistry.getPrometheusRegistry());
            assertNotNull(MetricsRegistry.getGraphiteRegistry());
        });

        Thread t2 = new Thread(() -> {
            assertNotNull(MetricsRegistry.getPrometheusRegistry());
            assertNotNull(MetricsRegistry.getGraphiteRegistry());
        });

        t1.start();
        t2.start();

        assertDoesNotThrow(() -> {
            t1.join();
            t2.join();
        });
    }
}
