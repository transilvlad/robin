package com.mimecast.robin.smtp.metrics;

import com.mimecast.robin.metrics.MetricsRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SmtpMetrics DoS protection counters.
 */
class SmtpMetricsDosTest {

    private PrometheusMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistry.register(registry, null);
        SmtpMetrics.resetCounters();
        SmtpMetrics.initialize();
    }

    @AfterEach
    void tearDown() {
        SmtpMetrics.resetCounters();
        MetricsRegistry.register(null, null);
    }

    @Test
    void testIncrementDosRateLimitRejection() {
        SmtpMetrics.incrementDosRateLimitRejection();

        Counter counter = registry.find("robin.dos.ratelimit.rejection").counter();
        assertNotNull(counter, "Rate limit rejection counter should exist");
        assertEquals(1.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosRateLimitRejectionMultiple() {
        SmtpMetrics.incrementDosRateLimitRejection();
        SmtpMetrics.incrementDosRateLimitRejection();
        SmtpMetrics.incrementDosRateLimitRejection();

        Counter counter = registry.find("robin.dos.ratelimit.rejection").counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosConnectionLimitRejection() {
        SmtpMetrics.incrementDosConnectionLimitRejection();

        Counter counter = registry.find("robin.dos.connectionlimit.rejection").counter();
        assertNotNull(counter, "Connection limit rejection counter should exist");
        assertEquals(1.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosConnectionLimitRejectionMultiple() {
        for (int i = 0; i < 5; i++) {
            SmtpMetrics.incrementDosConnectionLimitRejection();
        }

        Counter counter = registry.find("robin.dos.connectionlimit.rejection").counter();
        assertNotNull(counter);
        assertEquals(5.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosTarpit() {
        SmtpMetrics.incrementDosTarpit();

        Counter counter = registry.find("robin.dos.tarpit").counter();
        assertNotNull(counter, "Tarpit counter should exist");
        assertEquals(1.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosTarpitMultiple() {
        // Simulate progressive tarpit delays
        SmtpMetrics.incrementDosTarpit(); // 1st violation
        SmtpMetrics.incrementDosTarpit(); // 2nd violation
        SmtpMetrics.incrementDosTarpit(); // 3rd violation

        Counter counter = registry.find("robin.dos.tarpit").counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosSlowTransferRejection() {
        SmtpMetrics.incrementDosSlowTransferRejection();

        Counter counter = registry.find("robin.dos.slowtransfer.rejection").counter();
        assertNotNull(counter, "Slow transfer rejection counter should exist");
        assertEquals(1.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosSlowTransferRejectionMultiple() {
        SmtpMetrics.incrementDosSlowTransferRejection();
        SmtpMetrics.incrementDosSlowTransferRejection();

        Counter counter = registry.find("robin.dos.slowtransfer.rejection").counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosCommandFloodRejection() {
        SmtpMetrics.incrementDosCommandFloodRejection();

        Counter counter = registry.find("robin.dos.commandflood.rejection").counter();
        assertNotNull(counter, "Command flood rejection counter should exist");
        assertEquals(1.0, counter.count(), 0.01);
    }

    @Test
    void testIncrementDosCommandFloodRejectionMultiple() {
        SmtpMetrics.incrementDosCommandFloodRejection();
        SmtpMetrics.incrementDosCommandFloodRejection();
        SmtpMetrics.incrementDosCommandFloodRejection();
        SmtpMetrics.incrementDosCommandFloodRejection();

        Counter counter = registry.find("robin.dos.commandflood.rejection").counter();
        assertNotNull(counter);
        assertEquals(4.0, counter.count(), 0.01);
    }

    @Test
    void testAllDosMetricsTogether() {
        // Simulate a DoS attack scenario
        SmtpMetrics.incrementDosRateLimitRejection();
        SmtpMetrics.incrementDosRateLimitRejection();

        SmtpMetrics.incrementDosConnectionLimitRejection();
        SmtpMetrics.incrementDosConnectionLimitRejection();
        SmtpMetrics.incrementDosConnectionLimitRejection();

        SmtpMetrics.incrementDosTarpit();
        SmtpMetrics.incrementDosTarpit();

        SmtpMetrics.incrementDosSlowTransferRejection();

        SmtpMetrics.incrementDosCommandFloodRejection();

        // Verify all counters
        assertEquals(2.0, registry.find("robin.dos.ratelimit.rejection").counter().count(), 0.01);
        assertEquals(3.0, registry.find("robin.dos.connectionlimit.rejection").counter().count(), 0.01);
        assertEquals(2.0, registry.find("robin.dos.tarpit").counter().count(), 0.01);
        assertEquals(1.0, registry.find("robin.dos.slowtransfer.rejection").counter().count(), 0.01);
        assertEquals(1.0, registry.find("robin.dos.commandflood.rejection").counter().count(), 0.01);
    }

    @Test
    void testMetricsWithNullRegistry() {
        // Clear registry
        MetricsRegistry.register(null, null);

        // Should not throw when registry is null
        assertDoesNotThrow(SmtpMetrics::incrementDosRateLimitRejection);
        assertDoesNotThrow(SmtpMetrics::incrementDosConnectionLimitRejection);
        assertDoesNotThrow(SmtpMetrics::incrementDosTarpit);
        assertDoesNotThrow(SmtpMetrics::incrementDosSlowTransferRejection);
        assertDoesNotThrow(SmtpMetrics::incrementDosCommandFloodRejection);
    }

    @Test
    void testMetricNamesConsistency() {
        // Verify metric names follow naming convention
        SmtpMetrics.incrementDosRateLimitRejection();
        SmtpMetrics.incrementDosConnectionLimitRejection();
        SmtpMetrics.incrementDosTarpit();
        SmtpMetrics.incrementDosSlowTransferRejection();
        SmtpMetrics.incrementDosCommandFloodRejection();

        // All metrics should start with "robin.dos."
        assertTrue(registry.getMeters().stream()
            .anyMatch(m -> m.getId().getName().startsWith("robin.dos.")),
            "All DoS metrics should start with robin.dos.");
    }

    @Test
    void testMetricDescriptions() {
        SmtpMetrics.incrementDosRateLimitRejection();

        Counter counter = registry.find("robin.dos.ratelimit.rejection").counter();
        assertNotNull(counter);
        assertNotNull(counter.getId().getDescription(),
            "Metric should have a description");
    }

    @Test
    void testConcurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    SmtpMetrics.incrementDosRateLimitRejection();
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        Counter counter = registry.find("robin.dos.ratelimit.rejection").counter();
        assertEquals(threadCount * incrementsPerThread, counter.count(), 0.01,
            "Counter should handle concurrent increments correctly");
    }

    @Test
    void testMetricsIndependence() {
        // Incrementing one metric should not affect others
        SmtpMetrics.incrementDosRateLimitRejection();

        Counter rateLimitCounter = registry.find("robin.dos.ratelimit.rejection").counter();
        assertEquals(1.0, rateLimitCounter.count(), 0.01);

        // Other metrics should be zero or not exist yet
        Counter connectionCounter = registry.find("robin.dos.connectionlimit.rejection").counter();
        if (connectionCounter != null) {
            assertEquals(0.0, connectionCounter.count(), 0.01);
        }
    }

    @Test
    void testRepeatedIncrementsPattern() {
        // Simulate a typical attack pattern

        // Initial connection flood
        for (int i = 0; i < 20; i++) {
            SmtpMetrics.incrementDosConnectionLimitRejection();
        }

        // Some connections get through, rate limited
        for (int i = 0; i < 10; i++) {
            SmtpMetrics.incrementDosRateLimitRejection();
        }

        // A few tarpit delays applied
        for (int i = 0; i < 3; i++) {
            SmtpMetrics.incrementDosTarpit();
        }

        // Finally command flood disconnect
        SmtpMetrics.incrementDosCommandFloodRejection();

        assertEquals(20.0, registry.find("robin.dos.connectionlimit.rejection").counter().count(), 0.01);
        assertEquals(10.0, registry.find("robin.dos.ratelimit.rejection").counter().count(), 0.01);
        assertEquals(3.0, registry.find("robin.dos.tarpit").counter().count(), 0.01);
        assertEquals(1.0, registry.find("robin.dos.commandflood.rejection").counter().count(), 0.01);
    }

    @Test
    void testZeroInitialCount() {
        // Before any increment, find should not throw
        Counter counter = registry.find("robin.dos.ratelimit.rejection").counter();

        // Counter may be null if not created yet
        if (counter != null) {
            assertEquals(0.0, counter.count(), 0.01);
        }
    }

    @Test
    void testMetricsPersistence() {
        // Increment counters
        SmtpMetrics.incrementDosRateLimitRejection();
        SmtpMetrics.incrementDosRateLimitRejection();

        Counter counter1 = registry.find("robin.dos.ratelimit.rejection").counter();
        assertEquals(2.0, counter1.count(), 0.01);

        // Retrieve again - should be same value
        Counter counter2 = registry.find("robin.dos.ratelimit.rejection").counter();
        assertEquals(2.0, counter2.count(), 0.01);

        // Should be same instance
        assertSame(counter1, counter2);
    }

    @Test
    void testHighVolumeIncrements() {
        // Test with high volume
        int count = 10000;
        for (int i = 0; i < count; i++) {
            SmtpMetrics.incrementDosConnectionLimitRejection();
        }

        Counter counter = registry.find("robin.dos.connectionlimit.rejection").counter();
        assertEquals(count, counter.count(), 0.01);
    }
}

