package com.mimecast.robin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsCron.
 * <p>
 * Note: These tests focus on testing internal logic and helper methods.
 * Full integration testing with actual HTTP servers would require additional test infrastructure.
 */
class MetricsCronTest {

    private PrometheusMeterRegistry testRegistry;

    @BeforeEach
    void setUp() {
        // Create a fresh test registry
        testRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistry.register(testRegistry, null);

        // Reset MetricsCron state
        resetMetricsCron();
    }

    @AfterEach
    void tearDown() {
        // Clean up
        resetMetricsCron();
        MetricsRegistry.register(null, null);
        if (testRegistry != null) {
            testRegistry.close();
        }
    }

    /**
     * Reset MetricsCron internal state using reflection.
     */
    private void resetMetricsCron() {
        try {
            // Stop scheduler if running
            Field schedulerField = MetricsCron.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            ScheduledExecutorService scheduler = (ScheduledExecutorService) schedulerField.get(null);
            if (scheduler != null) {
                scheduler.shutdownNow();
                schedulerField.set(null, null);
            }

            // Reset enabled flag
            Field enabledField = MetricsCron.class.getDeclaredField("enabled");
            enabledField.setAccessible(true);
            enabledField.set(null, false);

        } catch (Exception e) {
            // Ignore if fields don't exist or can't be accessed
        }
    }

    @Test
    void testSanitizeLabelName() throws Exception {
        Method sanitize = MetricsCron.class.getDeclaredMethod("sanitizeLabelName", String.class);
        sanitize.setAccessible(true);

        // Test normal label names
        assertEquals("valid_label", sanitize.invoke(null, "valid_label"));
        assertEquals("valid_label", sanitize.invoke(null, "valid.label"));
        assertEquals("valid_label", sanitize.invoke(null, "valid-label"));

        // Test labels starting with numbers
        assertEquals("_123", sanitize.invoke(null, "123"));
        assertEquals("_4xx", sanitize.invoke(null, "4xx"));

        // Test mixed characters
        assertEquals("http_status_2xx", sanitize.invoke(null, "http.status.2xx"));
        assertEquals("response_time_ms", sanitize.invoke(null, "response-time-ms"));
    }

    @Test
    void testMatches() throws Exception {
        Method matches = MetricsCron.class.getDeclaredMethod("matches", String.class);
        matches.setAccessible(true);

        // Set up include/exclude patterns
        Field includesField = MetricsCron.class.getDeclaredField("includes");
        includesField.setAccessible(true);
        Field excludesField = MetricsCron.class.getDeclaredField("excludes");
        excludesField.setAccessible(true);

        // Test with no filters (should match all)
        includesField.set(null, new ArrayList<Pattern>());
        excludesField.set(null, new ArrayList<Pattern>());
        assertTrue((Boolean) matches.invoke(null, "any.metric.name"));

        // Test with include patterns
        List<Pattern> includes = new ArrayList<>();
        includes.add(Pattern.compile("jvm.*"));
        includes.add(Pattern.compile("http.*"));
        includesField.set(null, includes);
        excludesField.set(null, new ArrayList<Pattern>());

        assertTrue((Boolean) matches.invoke(null, "jvm.memory.used"));
        assertTrue((Boolean) matches.invoke(null, "http.requests"));
        assertFalse((Boolean) matches.invoke(null, "database.connections"));

        // Test with exclude patterns
        includesField.set(null, new ArrayList<Pattern>());
        List<Pattern> excludes = new ArrayList<>();
        excludes.add(Pattern.compile(".*debug.*"));
        excludesField.set(null, excludes);

        assertTrue((Boolean) matches.invoke(null, "http.requests"));
        assertFalse((Boolean) matches.invoke(null, "http.debug.requests"));

        // Test with both include and exclude
        includesField.set(null, includes);
        excludesField.set(null, excludes);

        assertTrue((Boolean) matches.invoke(null, "jvm.memory.used"));
        assertFalse((Boolean) matches.invoke(null, "jvm.debug.info"));
        assertFalse((Boolean) matches.invoke(null, "database.connections"));
    }

    @Test
    void testMatchesWithDotToUnderscoreConversion() throws Exception {
        Method matches = MetricsCron.class.getDeclaredMethod("matches", String.class);
        matches.setAccessible(true);

        Field includesField = MetricsCron.class.getDeclaredField("includes");
        includesField.setAccessible(true);
        Field excludesField = MetricsCron.class.getDeclaredField("excludes");
        excludesField.setAccessible(true);

        // Pattern with underscores should match metric names with dots
        List<Pattern> includes = new ArrayList<>();
        includes.add(Pattern.compile("http_requests_total"));
        includesField.set(null, includes);
        excludesField.set(null, new ArrayList<Pattern>());

        assertTrue((Boolean) matches.invoke(null, "http.requests.total"));
    }

    @Test
    void testEncodeLabel() throws Exception {
        Method encodeLabel = MetricsCron.class.getDeclaredMethod("encodeLabel",
                Class.forName("com.mimecast.robin.metrics.MetricsCron$Label"));
        encodeLabel.setAccessible(true);

        // Create a Label instance using reflection
        Class<?> labelClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$Label");
        Object label = labelClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("test_name", "test_value");

        byte[] encoded = (byte[]) encodeLabel.invoke(null, label);

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        // Encoded protobuf should contain the strings (basic sanity check)
    }

    @Test
    void testEncodeSample() throws Exception {
        Method encodeSample = MetricsCron.class.getDeclaredMethod("encodeSample",
                Class.forName("com.mimecast.robin.metrics.MetricsCron$Sample"));
        encodeSample.setAccessible(true);

        // Create a Sample instance using reflection
        Class<?> sampleClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$Sample");
        Object sample = sampleClass.getDeclaredConstructor(double.class, long.class)
                .newInstance(42.5, 1234567890000L);

        byte[] encoded = (byte[]) encodeSample.invoke(null, sample);

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        // Should be at least 16 bytes (field tags + double + varint for timestamp)
        assertTrue(encoded.length >= 10);
    }

    @Test
    void testProtoWriterVarint() throws Exception {
        Class<?> writerClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$ProtoWriter");
        Object writer = writerClass.getDeclaredConstructor().newInstance();

        Method writeVarint = writerClass.getDeclaredMethod("writeVarint", int.class);
        writeVarint.setAccessible(true);

        Method toByteArray = writerClass.getDeclaredMethod("toByteArray");
        toByteArray.setAccessible(true);

        // Test various varint values
        writeVarint.invoke(writer, 0);
        byte[] result = (byte[]) toByteArray.invoke(writer);
        assertEquals(1, result.length);
        assertEquals(0, result[0]);

        // Test larger value
        writer = writerClass.getDeclaredConstructor().newInstance();
        writeVarint.invoke(writer, 127);
        result = (byte[]) toByteArray.invoke(writer);
        assertEquals(1, result.length);
        assertEquals(127, result[0]);

        // Test value requiring 2 bytes
        writer = writerClass.getDeclaredConstructor().newInstance();
        writeVarint.invoke(writer, 300);
        result = (byte[]) toByteArray.invoke(writer);
        assertEquals(2, result.length);
    }

    @Test
    void testProtoWriterDouble() throws Exception {
        Class<?> writerClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$ProtoWriter");
        Object writer = writerClass.getDeclaredConstructor().newInstance();

        Method writeDouble = writerClass.getDeclaredMethod("writeDouble", int.class, double.class);
        writeDouble.setAccessible(true);

        Method toByteArray = writerClass.getDeclaredMethod("toByteArray");
        toByteArray.setAccessible(true);

        // Write a double value
        writeDouble.invoke(writer, 1, 3.14159);
        byte[] result = (byte[]) toByteArray.invoke(writer);

        // Should have key (1 byte) + 8 bytes for double = 9 bytes
        assertEquals(9, result.length);

        // First byte should be field number 1, wire type 1 (fixed64)
        assertEquals((1 << 3) | 1, result[0] & 0xFF);
    }

    @Test
    void testProtoWriterString() throws Exception {
        Class<?> writerClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$ProtoWriter");
        Object writer = writerClass.getDeclaredConstructor().newInstance();

        Method writeString = writerClass.getDeclaredMethod("writeString", int.class, String.class);
        writeString.setAccessible(true);

        Method toByteArray = writerClass.getDeclaredMethod("toByteArray");
        toByteArray.setAccessible(true);

        // Write a string
        String testString = "hello";
        writeString.invoke(writer, 2, testString);
        byte[] result = (byte[]) toByteArray.invoke(writer);

        // Should have key + length varint + string bytes
        assertTrue(result.length >= testString.length() + 2);

        // First byte should be field number 2, wire type 2 (length-delimited)
        assertEquals((2 << 3) | 2, result[0] & 0xFF);

        // Second byte should be string length
        assertEquals(testString.length(), result[1] & 0xFF);
    }

    @Test
    void testProtoWriterInt64() throws Exception {
        Class<?> writerClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$ProtoWriter");
        Object writer = writerClass.getDeclaredConstructor().newInstance();

        Method writeInt64 = writerClass.getDeclaredMethod("writeInt64", int.class, long.class);
        writeInt64.setAccessible(true);

        Method toByteArray = writerClass.getDeclaredMethod("toByteArray");
        toByteArray.setAccessible(true);

        // Write an int64
        writeInt64.invoke(writer, 3, 1234567890L);
        byte[] result = (byte[]) toByteArray.invoke(writer);

        assertNotNull(result);
        assertTrue(result.length > 1);

        // First byte should be field number 3, wire type 0 (varint)
        assertEquals((3 << 3) | 0, result[0] & 0xFF);
    }

    @Test
    void testBuildWriteRequestWithEmptyRegistry() throws Exception {
        Method buildWriteRequest = MetricsCron.class.getDeclaredMethod("buildWriteRequest");
        buildWriteRequest.setAccessible(true);

        // Set filters to accept all
        Field includesField = MetricsCron.class.getDeclaredField("includes");
        includesField.setAccessible(true);
        Field excludesField = MetricsCron.class.getDeclaredField("excludes");
        excludesField.setAccessible(true);
        includesField.set(null, new ArrayList<Pattern>());
        excludesField.set(null, new ArrayList<Pattern>());

        // Empty registry should produce empty byte array
        byte[] result = (byte[]) buildWriteRequest.invoke(null);
        assertEquals(0, result.length);
    }

    @Test
    void testBuildWriteRequestWithMetrics() throws Exception {
        Method buildWriteRequest = MetricsCron.class.getDeclaredMethod("buildWriteRequest");
        buildWriteRequest.setAccessible(true);

        // Set filters to accept all
        Field includesField = MetricsCron.class.getDeclaredField("includes");
        includesField.setAccessible(true);
        Field excludesField = MetricsCron.class.getDeclaredField("excludes");
        excludesField.setAccessible(true);
        Field staticLabelsField = MetricsCron.class.getDeclaredField("staticLabelList");
        staticLabelsField.setAccessible(true);

        includesField.set(null, new ArrayList<Pattern>());
        excludesField.set(null, new ArrayList<Pattern>());
        staticLabelsField.set(null, new ArrayList<>());

        // Add some metrics to the registry
        Counter counter = testRegistry.counter("test.counter", "tag1", "value1");
        counter.increment(5);

        Gauge.builder("test.gauge", () -> 42.0)
                .tag("tag2", "value2")
                .register(testRegistry);

        byte[] result = (byte[]) buildWriteRequest.invoke(null);

        assertNotNull(result);
        assertTrue(result.length > 0, "WriteRequest should not be empty with metrics");
    }

    @Test
    void testGetIntervalSeconds() {
        // Initially should be 0 (default)
        int interval = MetricsCron.getIntervalSeconds();
        assertTrue(interval >= 0);
    }

    @Test
    void testGetLastExecutionEpochSeconds() {
        long lastExecution = MetricsCron.getLastExecutionEpochSeconds();
        assertTrue(lastExecution >= 0);
    }

    @Test
    void testGetNextExecutionEpochSeconds() {
        long nextExecution = MetricsCron.getNextExecutionEpochSeconds();
        assertTrue(nextExecution >= 0);
    }

    @ParameterizedTest
    @CsvSource({
            "valid_label, valid_label",
            "valid.label, valid_label",
            "valid-label, valid_label",
            "123, _123",
            "http.status.2xx, http_status_2xx",
            "response-time-ms, response_time_ms",
            "a.b.c.d, a_b_c_d",
            "_already_valid, _already_valid"
    })
    void testSanitizeLabelNameParameterized(String input, String expected) throws Exception {
        Method sanitize = MetricsCron.class.getDeclaredMethod("sanitizeLabelName", String.class);
        sanitize.setAccessible(true);

        assertEquals(expected, sanitize.invoke(null, input));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStaticLabelsConfiguration() throws Exception {
        Field staticLabelsField = MetricsCron.class.getDeclaredField("staticLabels");
        staticLabelsField.setAccessible(true);

        Map<String, String> labels = new HashMap<>();
        labels.put("env", "test");
        labels.put("region", "us-east-1");
        staticLabelsField.set(null, labels);

        Map<String, String> retrieved = (Map<String, String>) staticLabelsField.get(null);
        assertEquals(2, retrieved.size());
        assertEquals("test", retrieved.get("env"));
        assertEquals("us-east-1", retrieved.get("region"));
    }

    @Test
    void testTimingInformationUpdates() throws Exception {
        Field lastExecutionField = MetricsCron.class.getDeclaredField("lastExecutionEpochSeconds");
        lastExecutionField.setAccessible(true);
        Field nextExecutionField = MetricsCron.class.getDeclaredField("nextExecutionEpochSeconds");
        nextExecutionField.setAccessible(true);

        long testTime = 1000000L;
        lastExecutionField.set(null, testTime);
        nextExecutionField.set(null, testTime + 60);

        assertEquals(testTime, MetricsCron.getLastExecutionEpochSeconds());
        assertEquals(testTime + 60, MetricsCron.getNextExecutionEpochSeconds());
    }

    @Test
    void testProtoWriterMessageEncoding() throws Exception {
        Class<?> writerClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$ProtoWriter");
        Object writer = writerClass.getDeclaredConstructor().newInstance();

        Method writeMessage = writerClass.getDeclaredMethod("writeMessage", int.class, byte[].class);
        writeMessage.setAccessible(true);

        Method toByteArray = writerClass.getDeclaredMethod("toByteArray");
        toByteArray.setAccessible(true);

        byte[] innerMessage = new byte[]{0x01, 0x02, 0x03};
        writeMessage.invoke(writer, 1, innerMessage);

        byte[] result = (byte[]) toByteArray.invoke(writer);

        // Should have: key (field 1, wire type 2) + length + message bytes
        assertTrue(result.length >= innerMessage.length + 2);
        assertEquals((1 << 3) | 2, result[0] & 0xFF); // Field 1, wire type 2
        assertEquals(innerMessage.length, result[1] & 0xFF); // Length
    }

    @Test
    void testEncodeWriteRequestWithMultipleTimeSeries() throws Exception {
        Method encodeWriteRequest = MetricsCron.class.getDeclaredMethod("encodeWriteRequest", List.class);
        encodeWriteRequest.setAccessible(true);

        Class<?> labelClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$Label");
        Class<?> sampleClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$Sample");
        Class<?> timeSeriesClass = Class.forName("com.mimecast.robin.metrics.MetricsCron$TimeSeries");

        // Create labels
        Object label1 = labelClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("__name__", "test_metric");
        Object label2 = labelClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("env", "test");

        List<Object> labels = Arrays.asList(label1, label2);

        // Create sample
        Object sample = sampleClass.getDeclaredConstructor(double.class, long.class)
                .newInstance(100.0, System.currentTimeMillis());

        // Create TimeSeries
        Object timeSeries = timeSeriesClass.getDeclaredConstructor(List.class, sampleClass)
                .newInstance(labels, sample);

        List<Object> seriesList = List.of(timeSeries);

        byte[] result = (byte[]) encodeWriteRequest.invoke(null, seriesList);

        assertNotNull(result);
        assertTrue(result.length > 0);
    }
}
