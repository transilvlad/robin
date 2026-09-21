package com.mimecast.robin.smtp.metrics;

import com.mimecast.robin.metrics.MetricsRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.parallel.Isolated;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmtpMetrics.
 */
@Isolated
class SmtpMetricsTest {

    private PrometheusMeterRegistry testRegistry;

    @BeforeEach
    void setUp() {
        testRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistry.register(testRegistry, null);
        SmtpMetrics.resetCounters();
        SmtpMetrics.initialize();
    }

    @AfterEach
    void tearDown() {
        SmtpMetrics.resetCounters();
        MetricsRegistry.register(null, null);
    }

    @Test
    void testIncrementEmailReceiptSuccess() {
        // Act
        SmtpMetrics.incrementEmailReceiptSuccess();
        SmtpMetrics.incrementEmailReceiptSuccess();
        SmtpMetrics.incrementEmailReceiptSuccess();

        // Assert
        Counter counter = testRegistry.find("robin.email.receipt.success").counter();
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
        Counter ioExceptionCounter = testRegistry.find("robin.email.receipt.exception")
                .tag("exception_type", "IOException")
                .counter();
        assertNotNull(ioExceptionCounter, "IOException counter should be registered");
        assertEquals(2.0, ioExceptionCounter.count(), 0.001, "IOException counter should have incremented 2 times");

        Counter npeCounter = testRegistry.find("robin.email.receipt.exception")
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
        Counter counter = testRegistry.find("robin.email.receipt.start").counter();
        assertNotNull(counter, "Start counter should be registered");
        assertEquals(3.0, counter.count(), 0.001, "Counter should have incremented 3 times");
    }

    @Test
    void testIncrementEmailReceiptLimit() {
        // Act
        SmtpMetrics.incrementEmailReceiptLimit();
        SmtpMetrics.incrementEmailReceiptLimit();

        // Assert
        Counter counter = testRegistry.find("robin.email.receipt.limit").counter();
        assertNotNull(counter, "Limit counter should be registered");
        assertEquals(2.0, counter.count(), 0.001, "Counter should have incremented 2 times");
    }

    @Test
    void testMetricsWithoutRegistry() {
        // Arrange
        MetricsRegistry.register(null, null);
        SmtpMetrics.resetCounters();

        // Act - Should not throw exception
        assertDoesNotThrow(SmtpMetrics::incrementEmailReceiptSuccess);
        assertDoesNotThrow(() -> SmtpMetrics.incrementEmailReceiptException("TestException"));
        assertDoesNotThrow(SmtpMetrics::incrementEmailReceiptLimit);
    }

    @Test
    void testMultipleSuccessIncrements() {
        // Act
        for (int i = 0; i < 10; i++) {
            SmtpMetrics.incrementEmailReceiptSuccess();
        }

        // Assert
        Counter counter = testRegistry.find("robin.email.receipt.success").counter();
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
        Counter ioCounter = testRegistry.find("robin.email.receipt.exception")
                .tag("exception_type", "IOException")
                .counter();
        assertNotNull(ioCounter);
        assertEquals(2.0, ioCounter.count(), 0.001);

        Counter socketCounter = testRegistry.find("robin.email.receipt.exception")
                .tag("exception_type", "SocketException")
                .counter();
        assertNotNull(socketCounter);
        assertEquals(1.0, socketCounter.count(), 0.001);

        Counter timeoutCounter = testRegistry.find("robin.email.receipt.exception")
                .tag("exception_type", "TimeoutException")
                .counter();
        assertNotNull(timeoutCounter);
        assertEquals(1.0, timeoutCounter.count(), 0.001);
    }

    @Test
    void testIncrementEmailRblRejection() {
        // Act
        SmtpMetrics.incrementEmailRblRejection();
        SmtpMetrics.incrementEmailRblRejection();

        // Assert
        Counter counter = testRegistry.find("robin.email.rbl.rejection").counter();
        assertNotNull(counter, "RBL rejection counter should be registered");
        assertEquals(2.0, counter.count(), 0.001, "Counter should have incremented 2 times");
    }

    @Test
    void testIncrementEmailVirusRejection() {
        // Act
        SmtpMetrics.incrementEmailVirusRejection();
        SmtpMetrics.incrementEmailVirusRejection();
        SmtpMetrics.incrementEmailVirusRejection();

        // Assert
        Counter counter = testRegistry.find("robin.email.virus.rejection").counter();
        assertNotNull(counter, "Virus rejection counter should be registered");
        assertEquals(3.0, counter.count(), 0.001, "Counter should have incremented 3 times");
    }

    @Test
    void testIncrementEmailSpamRejection() {
        // Act
        SmtpMetrics.incrementEmailSpamRejection();
        SmtpMetrics.incrementEmailSpamRejection();

        // Assert
        Counter counter = testRegistry.find("robin.email.spam.rejection").counter();
        assertNotNull(counter, "Spam rejection counter should be registered");
        assertEquals(2.0, counter.count(), 0.001, "Counter should have incremented 2 times");
    }

    @Test
    void testAllSecurityCounters() {
        // Act
        SmtpMetrics.incrementEmailRblRejection();
        SmtpMetrics.incrementEmailVirusRejection();
        SmtpMetrics.incrementEmailVirusRejection();
        SmtpMetrics.incrementEmailSpamRejection();
        SmtpMetrics.incrementEmailSpamRejection();
        SmtpMetrics.incrementEmailSpamRejection();

        // Assert
        Counter rblCounter = testRegistry.find("robin.email.rbl.rejection").counter();
        assertNotNull(rblCounter);
        assertEquals(1.0, rblCounter.count(), 0.001);

        Counter virusCounter = testRegistry.find("robin.email.virus.rejection").counter();
        assertNotNull(virusCounter);
        assertEquals(2.0, virusCounter.count(), 0.001);

        Counter spamCounter = testRegistry.find("robin.email.spam.rejection").counter();
        assertNotNull(spamCounter);
        assertEquals(3.0, spamCounter.count(), 0.001);
    }

    @Test
    void testIncrementMessageOutcome() {
        // Act
        SmtpMetrics.incrementMessageOutcome("accepted", "data");
        SmtpMetrics.incrementMessageOutcome("accepted", "data");
        SmtpMetrics.incrementMessageOutcome("perm_rejected", "data");

        // Assert
        Counter acceptedCounter = testRegistry.find("robin.email.message.outcome")
                .tag("outcome", "accepted")
                .tag("protocol", "data")
                .counter();
        assertNotNull(acceptedCounter, "Accepted message outcome counter should be registered");
        assertEquals(2.0, acceptedCounter.count(), 0.001);

        Counter rejectedCounter = testRegistry.find("robin.email.message.outcome")
                .tag("outcome", "perm_rejected")
                .tag("protocol", "data")
                .counter();
        assertNotNull(rejectedCounter, "Rejected message outcome counter should be registered");
        assertEquals(1.0, rejectedCounter.count(), 0.001);
    }

    @Test
    void testRecordMessageDuration() {
        // Act
        SmtpMetrics.recordMessageDuration("accepted", 25);
        SmtpMetrics.recordMessageDuration("accepted", 75);

        // Assert
        Timer timer = testRegistry.find("robin.email.message.duration")
                .tag("outcome", "accepted")
                .timer();
        assertNotNull(timer, "Message duration timer should be registered");
        assertEquals(2, timer.count());
        assertEquals(100.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void testIncrementConnectionOutcome() {
        // Act
        SmtpMetrics.incrementConnectionOutcome("smtp", "quit");
        SmtpMetrics.incrementConnectionOutcome("smtp", "quit");
        SmtpMetrics.incrementConnectionOutcome("smtps", "client_disconnect");

        // Assert
        Counter quitCounter = testRegistry.find("robin.email.connection.outcome")
                .tag("listener", "smtp")
                .tag("termination_reason", "quit")
                .counter();
        assertNotNull(quitCounter, "Connection outcome counter should be registered");
        assertEquals(2.0, quitCounter.count(), 0.001);

        Counter disconnectCounter = testRegistry.find("robin.email.connection.outcome")
                .tag("listener", "smtps")
                .tag("termination_reason", "client_disconnect")
                .counter();
        assertNotNull(disconnectCounter);
        assertEquals(1.0, disconnectCounter.count(), 0.001);
    }

    @Test
    void testRecordConnectionDuration() {
        // Act
        SmtpMetrics.recordConnectionDuration("smtp", 10);
        SmtpMetrics.recordConnectionDuration("smtp", 40);

        // Assert
        Timer timer = testRegistry.find("robin.email.connection.duration")
                .tag("listener", "smtp")
                .timer();
        assertNotNull(timer, "Connection duration timer should be registered");
        assertEquals(2, timer.count());
        assertEquals(50.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void testOutcomeMetricsWithoutRegistry() {
        // Arrange
        MetricsRegistry.register(null, null);
        SmtpMetrics.resetCounters();

        // Act - Should not throw exception
        assertDoesNotThrow(() -> SmtpMetrics.incrementMessageOutcome("accepted", "data"));
        assertDoesNotThrow(() -> SmtpMetrics.recordMessageDuration("accepted", 10));
        assertDoesNotThrow(() -> SmtpMetrics.incrementConnectionOutcome("smtp", "quit"));
        assertDoesNotThrow(() -> SmtpMetrics.recordConnectionDuration("smtp", 10));
    }
}
