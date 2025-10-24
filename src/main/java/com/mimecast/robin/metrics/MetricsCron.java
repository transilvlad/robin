package com.mimecast.robin.metrics;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.Magic;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Periodically collects Micrometer metrics and pushes them to a Prometheus-compatible backend
 * using the Remote Write protocol (protobuf) with optional Snappy block compression.
 *
 * <p>Configuration is read from Config.getServer().getPrometheus() (prometheus.json5), supporting:
 * <ul>
 *   <li>enabled (boolean)</li>
 *   <li>remoteWriteUrl (string)</li>
 *   <li>intervalSeconds, timeoutSeconds (numbers)</li>
 *   <li>compress (boolean)</li>
 *   <li>labels (map of static labels)</li>
 *   <li>headers (map of extra HTTP headers)</li>
 *   <li>bearerToken | basicAuthUser/basicAuthPassword</li>
 *   <li>tenantHeaderName/tenantHeaderValue (convenience for multi-tenant backends)</li>
 *   <li>include/exclude (regex arrays matched against normalized metric names with '_' instead of '.')</li>
 * </ul>
 */
public class MetricsCron {
    private static final Logger log = LogManager.getLogger(MetricsCron.class);

    private static final MediaType MEDIA_PROTOBUF = MediaType.parse("application/x-protobuf");

    private static volatile ScheduledExecutorService scheduler;
    private static volatile OkHttpClient httpClient;
    private static volatile Headers staticHeaders;

    // Timing information for health checks.
    private static volatile long lastExecutionEpochSeconds = 0L;
    private static volatile long nextExecutionEpochSeconds = 0L;

    // Configuration properties.
    private static volatile boolean enabled;
    private static volatile String remoteWriteUrl;
    private static volatile int intervalSeconds;
    private static volatile int timeoutSeconds;
    private static volatile Map<String, String> staticLabels;
    private static volatile List<Label> staticLabelList;
    private static volatile Map<String, String> headers;
    private static volatile String bearerToken;
    private static volatile String basicUser;
    private static volatile String basicPass;
    private static volatile String tenantHeaderName;
    private static volatile String tenantHeaderValue;
    private static volatile List<Pattern> includes;
    private static volatile List<Pattern> excludes;
    private static volatile boolean compress;

    /**
     * Initializes and starts the metrics push scheduler.
     * Called by Server.startup(); safe to call once.
     *
     * @param config BasicConfig instance.
     */
    public static synchronized void run(BasicConfig config) {
        if (scheduler != null) return;

        loadConfig(config);
        if (!enabled) {
            log.info("MetricsCron disabled (prometheus.enabled=false)");
            return;
        }

        log.info(
                "MetricsCron starting: url={}, intervalSeconds={}, timeoutSeconds={}, includes={}, excludes={}",
                remoteWriteUrl, intervalSeconds, timeoutSeconds,
                includes.stream().map(Pattern::pattern).collect(Collectors.toList()),
                excludes.stream().map(Pattern::pattern).collect(Collectors.toList())
        );

        scheduler = Executors.newScheduledThreadPool(1);

        Runnable task = () -> {
            try {
                lastExecutionEpochSeconds = Instant.now().getEpochSecond();
                nextExecutionEpochSeconds = lastExecutionEpochSeconds + intervalSeconds;
                pushOnce();
            } catch (Exception e) {
                log.error("MetricsCron push error: {}", e.getMessage(), e.getMessage());
            }
        };

        // Delay first push to allow endpoint initialization.
        nextExecutionEpochSeconds = Instant.now().getEpochSecond() + intervalSeconds;
        scheduler.scheduleAtFixedRate(task, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (scheduler != null) {
                scheduler.shutdown();
            }
        }));
    }

    /**
     * Loads configuration from prometheus.json5 and initializes HTTP client and headers.
     *
     * @param config BasicConfig instance.
     */
    private static void loadConfig(BasicConfig config) {
        enabled = config.getBooleanProperty("enabled", false);
        remoteWriteUrl = config.getStringProperty("remoteWriteUrl", "");
        intervalSeconds = Math.toIntExact(config.getLongProperty("intervalSeconds", 15L));
        timeoutSeconds = Math.toIntExact(config.getLongProperty("timeoutSeconds", 10L));
        compress = config.getBooleanProperty("compress", true);

        // Get session for magic replacements.
        Session session = Factories.getSession();

        // Parse static labels.
        Map<String, String> labels = new HashMap<>();
        if (config.getMapProperty("labels") != null) {
            for (Object o : config.getMapProperty("labels").entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                labels.put(String.valueOf(e.getKey()), Magic.magicReplace(String.valueOf(e.getValue()), session));
            }
        }
        staticLabels = labels;

        // Pre-sanitize static labels for reuse.
        List<Label> prebuilt = new ArrayList<>(staticLabels.size());
        for (Map.Entry<String, String> e : staticLabels.entrySet()) {
            prebuilt.add(new Label(sanitizeLabelName(e.getKey()), e.getValue()));
        }
        staticLabelList = prebuilt;

        // Parse additional headers.
        Map<String, String> hdrs = new HashMap<>();
        if (config.getMapProperty("headers") != null) {
            for (Object o : config.getMapProperty("headers").entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                hdrs.put(String.valueOf(e.getKey()), Magic.magicReplace(String.valueOf(e.getValue()), session));
            }
        }
        headers = hdrs;

        // Parse authentication settings.
        bearerToken = Magic.magicReplace(config.getStringProperty("bearerToken", ""), session);
        basicUser = Magic.magicReplace(config.getStringProperty("basicAuthUser", ""), session);
        basicPass = Magic.magicReplace(config.getStringProperty("basicAuthPassword", ""), session);

        // Parse multi-tenancy header.
        tenantHeaderName = Magic.magicReplace(config.getStringProperty("tenantHeaderName", ""), session);
        tenantHeaderValue = Magic.magicReplace(config.getStringProperty("tenantHeaderValue", ""), session);

        // Compile include/exclude filters.
        includes = new ArrayList<>();
        excludes = new ArrayList<>();
        List<?> inclAny = config.getListProperty("include");
        List<?> exclAny = config.getListProperty("exclude");
        for (Object pObj : (inclAny != null ? inclAny : List.of())) {
            String p = String.valueOf(pObj);
            try {
                includes.add(Pattern.compile(p));
            } catch (PatternSyntaxException ex) {
                log.warn("Invalid include pattern '{}': {}", p, ex.getMessage());
            }
        }
        for (Object pObj : (exclAny != null ? exclAny : List.of())) {
            String p = String.valueOf(pObj);
            try {
                excludes.add(Pattern.compile(p));
            } catch (PatternSyntaxException ex) {
                log.warn("Invalid exclude pattern '{}': {}", p, ex.getMessage());
            }
        }

        if (remoteWriteUrl == null || remoteWriteUrl.isBlank()) {
            log.warn("MetricsCron enabled but no remoteWriteUrl configured. Disabling.");
            enabled = false;
        }

        // Build HTTP client with configured timeout.
        httpClient = new OkHttpClient.Builder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();

        // Build static headers for reuse across all requests.
        Headers.Builder hb = new Headers.Builder()
                .add("Content-Type", "application/x-protobuf")
                .add("X-Prometheus-Remote-Write-Version", "0.1.0");
        if (compress) {
            hb.add("Content-Encoding", "snappy");
        }

        if (!bearerToken.isBlank()) {
            hb.add("Authorization", "Bearer " + bearerToken);
        } else if (!basicUser.isBlank() || !basicPass.isBlank()) {
            String creds = Base64.getEncoder().encodeToString((basicUser + ":" + basicPass).getBytes());
            hb.add("Authorization", "Basic " + creds);
        }
        if (!tenantHeaderName.isBlank() && !tenantHeaderValue.isBlank()) {
            hb.add(tenantHeaderName, tenantHeaderValue);
        }
        for (Map.Entry<String, String> e : headers.entrySet()) hb.add(e.getKey(), e.getValue());
        staticHeaders = hb.build();
    }

    /**
     * Checks if a metric name passes include/exclude filters after normalizing dots to underscores.
     */
    private static boolean matches(String name) {
        String norm = name.replace('.', '_');
        if (!includes.isEmpty() && includes.stream().noneMatch(p -> p.matcher(norm).matches())) return false;
        return excludes.isEmpty() || excludes.stream().noneMatch(p -> p.matcher(norm).matches());
    }

    /**
     * Collects metrics, encodes to protobuf, compresses, and posts to the remote write endpoint.
     */
    private static void pushOnce() throws IOException {
        if (MetricsRegistry.getPrometheusRegistry() == null) {
            log.debug("MetricsCron: Prometheus registry not ready yet");
            return;
        }

        // Build WriteRequest from registry meters.
        byte[] body = buildWriteRequest();
        if (body.length == 0) {
            log.debug("MetricsCron: Nothing to push");
            return;
        }

        byte[] compressed;
        if (compress) {
            // Prometheus remote_write expects raw Snappy block compression (NOT framed).
            try {
                compressed = Snappy.compress(body);
            } catch (IOException e) {
                log.warn("MetricsCron: Snappy compression failed, sending uncompressed. error={}", e.getMessage());
                compressed = body;
            }
        } else {
            // No compression when disabled.
            compressed = body;
        }

        // Build the request with precomputed static headers.
        RequestBody reqBody = RequestBody.create(compressed, MEDIA_PROTOBUF);
        Request request = new Request.Builder()
                .url(remoteWriteUrl)
                .post(reqBody)
                .headers(staticHeaders)
                .build();

        // Execute and log outcome.
        try (Response resp = httpClient.newCall(request).execute()) {
            int code = resp.code();
            if (code >= 200 && code < 300) {
                log.debug("MetricsCron push OK: status={}", code);
            } else {
                String respBody = resp.body() != null ? resp.body().string() : "";
                log.warn("MetricsCron push failed: status={}, body={}", code, respBody);
            }
        }
    }

    /**
     * Builds a protobuf WriteRequest from current registry meters with labels and samples.
     */
    private static byte[] buildWriteRequest() {
        List<TimeSeries> series = new ArrayList<>();
        final long tsMillis = System.currentTimeMillis();

        // Snapshot meters; iterate once and convert each measurement into a sample.
        for (Meter meter : com.mimecast.robin.metrics.MetricsRegistry.getPrometheusRegistry().getMeters()) {
            final String rawName = meter.getId().getName();
            if (!matches(rawName)) continue;

            // Base labels for this meter (including metric name and static labels).
            final String metricName = rawName.replace('.', '_');
            List<Label> base = new ArrayList<>(1 + meter.getId().getTags().size() + staticLabelList.size());
            base.add(new Label("__name__", metricName));
            for (Tag t : meter.getId().getTags()) base.add(new Label(sanitizeLabelName(t.getKey()), t.getValue()));
            // Reuse prebuilt static labels
            base.addAll(staticLabelList);

            // Each measurement becomes a sample; add a stat label to distinguish.
            for (Measurement m : meter.measure()) {
                List<Label> labelsForSample = new ArrayList<>(base.size() + 1);
                labelsForSample.addAll(base);
                labelsForSample.add(new Label("stat", m.getStatistic().getTagValueRepresentation()));
                series.add(new TimeSeries(labelsForSample, new Sample(m.getValue(), tsMillis)));
            }
        }

        if (series.isEmpty()) return new byte[0];
        return encodeWriteRequest(series);
    }

    /**
     * Sanitizes label names to match [a-zA-Z_][a-zA-Z0-9_]* by replacing dots/dashes and prefixing if needed.
     */
    private static String sanitizeLabelName(String s) {
        String r = s.replace('.', '_').replace('-', '_');
        if (!r.isEmpty() && !Character.isLetter(r.charAt(0)) && r.charAt(0) != '_') {
            r = "_" + r;
        }
        return r;
    }

    /**
     * Encode a WriteRequest with repeated TimeSeries (field 1).
     */
    private static byte[] encodeWriteRequest(List<TimeSeries> series) {
        ProtoWriter w = new ProtoWriter();
        for (TimeSeries ts : series) w.writeMessage(1, encodeTimeSeries(ts));
        return w.toByteArray();
    }

    /**
     * Encode a TimeSeries: repeated Label (1), repeated Sample (2).
     */
    private static byte[] encodeTimeSeries(TimeSeries ts) {
        ProtoWriter w = new ProtoWriter();
        for (Label l : ts.labels) w.writeMessage(1, encodeLabel(l));
        for (Sample s : ts.samples) w.writeMessage(2, encodeSample(s));
        return w.toByteArray();
    }

    /**
     * Encode a Label: name (1), value (2).
     */
    private static byte[] encodeLabel(Label l) {
        ProtoWriter w = new ProtoWriter();
        w.writeString(1, l.name);
        w.writeString(2, l.value);
        return w.toByteArray();
    }

    /**
     * Encode a Sample: value (1, double), timestamp_ms (2, int64).
     */
    private static byte[] encodeSample(Sample s) {
        ProtoWriter w = new ProtoWriter();
        w.writeDouble(1, s.value);
        w.writeInt64(2, s.timestampMs);
        return w.toByteArray();
    }

    /**
     * POJO: name/value label pair.
     */
    private static class Label {
        final String name;
        final String value;

        Label(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * POJO: a single sample value with timestamp in millis.
     */
    private static class Sample {
        final double value;
        final long timestampMs;

        Sample(double value, long timestampMs) {
            this.value = value;
            this.timestampMs = timestampMs;
        }
    }

    /**
     * POJO: one time series with its labels and samples.
     */
    private static class TimeSeries {
        final List<Label> labels;
        final List<Sample> samples;

        TimeSeries(List<Label> labels, Sample sample) {
            this.labels = labels;
            this.samples = List.of(sample);
        }
    }

    /**
     * Tiny protobuf writer sufficient for the WriteRequest/TimeSeries/Label/Sample envelope.
     * Supports: varint, fixed64 (double, little-endian), and length-delimited messages/strings.
     */
    static class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        byte[] toByteArray() {
            return out.toByteArray();
        }

        void writeMessage(int fieldNumber, byte[] messageBytes) {
            writeKey(fieldNumber, 2); // wire type 2 = length-delimited
            writeVarint(messageBytes.length);
            out.write(messageBytes, 0, messageBytes.length);
        }

        void writeString(int fieldNumber, String value) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeKey(fieldNumber, 2);
            writeVarint(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        void writeInt64(int fieldNumber, long value) {
            writeKey(fieldNumber, 0); // wire type 0 = varint
            writeVarint64(value);
        }

        void writeDouble(int fieldNumber, double value) {
            writeKey(fieldNumber, 1); // wire type 1 = 64-bit
            long bits = Double.doubleToLongBits(value);
            // Little-endian write (least significant byte first)
            out.write((int) (bits & 0xFF));
            out.write((int) ((bits >>> 8) & 0xFF));
            out.write((int) ((bits >>> 16) & 0xFF));
            out.write((int) ((bits >>> 24) & 0xFF));
            out.write((int) ((bits >>> 32) & 0xFF));
            out.write((int) ((bits >>> 40) & 0xFF));
            out.write((int) ((bits >>> 48) & 0xFF));
            out.write((int) ((bits >>> 56) & 0xFF));
        }

        void writeKey(int fieldNumber, int wireType) {
            int key = (fieldNumber << 3) | wireType;
            writeVarint(key);
        }

        void writeVarint(int value) {
            long v = value & 0xFFFFFFFFL;
            writeVarint64(v);
        }

        void writeVarint64(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0L) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
    }

    /**
     * Getters for health check access.
     *
     * @return int seconds interval, last and next execution epoch seconds.
     */
    public static int getIntervalSeconds() {
        return intervalSeconds;
    }

    /**
     * Get last execution epoch seconds.
     *
     * @return long epoch seconds of last execution.
     */
    public static long getLastExecutionEpochSeconds() {
        return lastExecutionEpochSeconds;
    }

    /**
     * Get next scheduled execution epoch seconds.
     *
     * @return long epoch seconds of next execution.
     */
    public static long getNextExecutionEpochSeconds() {
        return nextExecutionEpochSeconds;
    }
}
