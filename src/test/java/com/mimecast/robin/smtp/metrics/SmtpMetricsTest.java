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
 * Tests for SmtpMetrics.
 */
class SmtpMetricsTest {

    private PrometheusMeterRegistry testRegistry;

    @BeforeEach
    void setUp() {
        testRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistry.register(testRegistry, null);
        SmtpMetrics.resetCounters();
    }

    @AfterEach
    void tearDown() {
        MetricsRegistry.register(null, null);
        SmtpMetrics.resetCounters();
    }

    @Test
    void testIncrementEmailReceiptSuccess() {
        // Act
        SmtpMetrics.incrementEmailReceiptSuccess();
        SmtpMetrics.incrementEmailReceiptSuccess();
        SmtpMetrics.incrementEmailReceiptSuccess();

        // Assert
        Counter counter = testRegistry.find("smtp.email.receipt.success").counter();
        assertNotNull(counter, "Success counter should be registered");
        assertEquals(3.0, counter.count(), 0.001, "Counter should have incremented 3 times");
    }

    @Test
    void testIncrementEmailReceiptException() {
        // Act
        SmtpMetrics.incrementEmailReceiptException("IOException");
        SmtpMetrics.incrementEmailReceiptException("IOException");
        SmtpMetrics.incrementEmailReceiptException("NullPointerException");

        // Assert
        Counter ioExceptionCounter = testRegistry.find("smtp.email.receipt.exceptions")
                .tag("exception_type", "IOException")
                .counter();
        assertNotNull(ioExceptionCounter, "IOException counter should be registered");
        assertEquals(2.0, ioExceptionCounter.count(), 0.001, "IOException counter should have incremented 2 times");

        Counter npeCounter = testRegistry.find("smtp.email.receipt.exceptions")
                .tag("exception_type", "NullPointerException")
                .counter();
        assertNotNull(npeCounter, "NullPointerException counter should be registered");
        assertEquals(1.0, npeCounter.count(), 0.001, "NullPointerException counter should have incremented 1 time");
    }

    @Test
    void testIncrementEmailReceiptStart() {
        // Act
        SmtpMetrics.incrementEmailReceiptStart();
        SmtpMetrics.incrementEmailReceiptStart();
        SmtpMetrics.incrementEmailReceiptStart();

        // Assert
        Counter counter = testRegistry.find("smtp.email.receipt.start").counter();
        assertNotNull(counter, "Start counter should be registered");
        assertEquals(3.0, counter.count(), 0.001, "Counter should have incremented 3 times");
    }

    @Test
    void testIncrementEmailReceiptLimit() {
        // Act
        SmtpMetrics.incrementEmailReceiptLimit();
        SmtpMetrics.incrementEmailReceiptLimit();

        // Assert
        Counter counter = testRegistry.find("smtp.email.receipt.limit").counter();
        assertNotNull(counter, "Limit counter should be registered");
        assertEquals(2.0, counter.count(), 0.001, "Counter should have incremented 2 times");
    }

    @Test
    void testMetricsWithoutRegistry() {
        // Arrange
        MetricsRegistry.register(null, null);
        SmtpMetrics.resetCounters();

        // Act - Should not throw exception
        assertDoesNotThrow(() -> SmtpMetrics.incrementEmailReceiptSuccess());
        assertDoesNotThrow(() -> SmtpMetrics.incrementEmailReceiptException("TestException"));
        assertDoesNotThrow(() -> SmtpMetrics.incrementEmailReceiptLimit());
    }

    @Test
    void testMultipleSuccessIncrements() {
        // Act
        for (int i = 0; i < 10; i++) {
            SmtpMetrics.incrementEmailReceiptSuccess();
        }

        // Assert
        Counter counter = testRegistry.find("smtp.email.receipt.success").counter();
        assertNotNull(counter);
        assertEquals(10.0, counter.count(), 0.001);
    }

    @Test
    void testMixedExceptionTypes() {
        // Act
        SmtpMetrics.incrementEmailReceiptException("IOException");
        SmtpMetrics.incrementEmailReceiptException("SocketException");
        SmtpMetrics.incrementEmailReceiptException("TimeoutException");
        SmtpMetrics.incrementEmailReceiptException("IOException");

        // Assert
        assertEquals(2.0, testRegistry.find("smtp.email.receipt.exceptions")
                .tag("exception_type", "IOException")
                .counter().count(), 0.001);

        assertEquals(1.0, testRegistry.find("smtp.email.receipt.exceptions")
                .tag("exception_type", "SocketException")
                .counter().count(), 0.001);

        assertEquals(1.0, testRegistry.find("smtp.email.receipt.exceptions")
                .tag("exception_type", "TimeoutException")
                .counter().count(), 0.001);
    }
}
