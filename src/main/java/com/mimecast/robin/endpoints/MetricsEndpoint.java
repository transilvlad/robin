package com.mimecast.robin.endpoints;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RetryScheduler;
import com.mimecast.robin.smtp.SmtpListener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Monitoring and management endpoint.
 *
 * <p>This class sets up an embedded HTTP server to expose various application metrics and operational endpoints.
 * <p>It provides metrics in Prometheus and Graphite formats, along with a simple web UI for visualization.
 * <p>Additionally, it offers endpoints for health checks, environment variables, system properties, thread dumps, and heap dumps.
 */
public class MetricsEndpoint {
    private static final Logger log = LogManager.getLogger(MetricsEndpoint.class);

    private HttpServer server;
    private PrometheusMeterRegistry prometheusRegistry;
    private GraphiteMeterRegistry graphiteRegistry;
    private JvmGcMetrics jvmGcMetrics;
    private final long startTime = System.currentTimeMillis();

    /**
     * Starts the embedded HTTP server for the metrics and management endpoint.
     * <p>This method initializes metric registries, binds JVM metrics, creates HTTP contexts for all endpoints,
     * and sets up shutdown hooks for graceful termination.
     *
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start() throws IOException {
        prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        graphiteRegistry = getGraphiteMeterRegistry();

        bindJvmMetrics();

        int metricsPort = Config.getServer().getMetricsPort();
        server = HttpServer.create(new InetSocketAddress(metricsPort), 10);

        createContexts();

        shutdownHooks();

        new Thread(server::start).start();
        log.info("UI available at http://localhost:{}/metrics", metricsPort);
        log.info("Graphite data available at http://localhost:{}/graphite", metricsPort);
        log.info("Prometheus data available at http://localhost:{}/prometheus", metricsPort);
        log.info("Environment variable available at http://localhost:{}/env", metricsPort);
        log.info("System properties available at http://localhost:{}/sysprops", metricsPort);
        log.info("Threads dump available at http://localhost:{}/threads", metricsPort);
        log.info("Heap dump available at http://localhost:{}/heapdump", metricsPort);
        log.info("Health available at http://localhost:{}/health", metricsPort);
    }

    /**
     * Binds standard JVM metrics to the Prometheus and Graphite registries.
     * <p>This includes memory usage, garbage collection, thread metrics, and processor metrics.
     */
    private void bindJvmMetrics() {
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        new JvmMemoryMetrics().bindTo(graphiteRegistry);
        jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(prometheusRegistry);
        jvmGcMetrics.bindTo(graphiteRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(graphiteRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(graphiteRegistry);
    }

    /**
     * Creates and registers HTTP context handlers for all supported endpoints.
     */
    private void createContexts() {
        server.createContext("/", this::handleLandingPage);
        server.createContext("/metrics", this::handleMetricsUi);
        server.createContext("/graphite", this::handleGraphite);
        server.createContext("/prometheus", this::handlePrometheus);
        server.createContext("/env", this::handleEnv);
        server.createContext("/sysprops", this::handleSysProps);
        server.createContext("/threads", this::handleThreads);
        server.createContext("/heapdump", this::handleHeapDump);
        server.createContext("/health", this::handleHealth);
    }

    /**
     * Handles requests for the landing page, which lists all available endpoints.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleLandingPage(HttpExchange exchange) throws IOException {
        try {
            String response = readResourceFile("metrics-endpoints-ui.html");
            sendResponse(exchange, 200, "text/html; charset=utf-8", response);
        } catch (IOException e) {
            log.error("Could not read metrics-endpoints-ui.html", e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Handles requests for the metrics UI page.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleMetricsUi(HttpExchange exchange) throws IOException {
        try {
            String response = readResourceFile("metrics-ui.html");
            sendResponse(exchange, 200, "text/html; charset=utf-8", response);
        } catch (IOException e) {
            log.error("Could not read metrics-ui.html", e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Handles requests for metrics in Graphite plain text format.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleGraphite(HttpExchange exchange) throws IOException {
        StringBuilder response = new StringBuilder();
        graphiteRegistry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> {
            String name = meter.getId().getName().replaceAll("\\.", "_");
            response.append(name).append(" ").append(measurement.getValue()).append(" ").append(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())).append("\n");
        }));
        sendResponse(exchange, 200, "text/plain; charset=utf-8", response.toString());
    }

    /**
     * Handles requests for metrics in Prometheus exposition format.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handlePrometheus(HttpExchange exchange) throws IOException {
        String response = prometheusRegistry.scrape();
        sendResponse(exchange, 200, "text/plain; charset=utf-8", response);
    }

    /**
     * Handles requests for environment variables.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleEnv(HttpExchange exchange) throws IOException {
        String response = System.getenv().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        sendResponse(exchange, 200, "text/plain; charset=utf-8", response);
    }

    /**
     * Handles requests for Java system properties.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleSysProps(HttpExchange exchange) throws IOException {
        String response = System.getProperties().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        sendResponse(exchange, 200, "text/plain; charset=utf-8", response);
    }

    /**
     * Handles requests for a thread dump of the application.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleThreads(HttpExchange exchange) throws IOException {
        String response = getThreadDump();
        sendResponse(exchange, 200, "text/plain; charset=utf-8", response);
    }

    /**
     * Handles requests to trigger and save a heap dump.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleHeapDump(HttpExchange exchange) throws IOException {
        try {
            String path = "heapdump-" + System.currentTimeMillis() + ".hprof";
            HotSpotDiagnostic.getDiagnostic().dumpHeap(path, true);
            String response = "Heap dump created at: " + path;
            sendResponse(exchange, 200, "text/plain", response);
        } catch (Exception e) {
            log.error("Could not create heap dump", e);
            sendError(exchange, 500, "Could not create heap dump: " + e.getMessage());
        }
    }

    /**
     * Handles requests for the application's health status.
     * <p>Provides a JSON response with the status, uptime, and number of active listeners.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        Duration uptime = Duration.ofMillis(System.currentTimeMillis() - startTime);
        String uptimeString = String.format("%dd %dh %dm %ds",
                uptime.toDays(),
                uptime.toHoursPart(),
                uptime.toMinutesPart(),
                uptime.toSecondsPart());

        List<SmtpListener> listeners = Server.getListeners();
        String listenersJson = listeners.stream()
                .map(listener -> String.format("{\"port\":%d,\"threadPool\":{\"core\":%d,\"max\":%d,\"size\":%d,\"largest\":%d,\"active\":%d,\"queue\":%d,\"taskCount\":%d,\"completed\":%d,\"keepAliveSeconds\":%d}}",
                        listener.getPort(),
                        listener.getCorePoolSize(),
                        listener.getMaximumPoolSize(),
                        listener.getPoolSize(),
                        listener.getLargestPoolSize(),
                        listener.getActiveThreads(),
                        listener.getQueueSize(),
                        listener.getTaskCount(),
                        listener.getCompletedTaskCount(),
                        listener.getKeepAliveSeconds()))
                .collect(Collectors.joining(",", "[", "]"));

        // Queue and scheduler stats
        long queueSize = RelayQueueCron.getQueueSize();
        Map<Integer, Long> histogram = RelayQueueCron.getRetryHistogram();
        String histogramJson = histogram.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> String.format("\"%d\":%d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));

        String schedulerConfigJson = String.format("{\"totalRetries\":%d,\"firstWaitMinutes\":%d,\"growthFactor\":%.2f}",
                RetryScheduler.getTotalRetries(),
                RetryScheduler.getFirstWaitMinutes(),
                RetryScheduler.getGrowthFactor());

        String cronJson = String.format("{\"initialDelaySeconds\":%d,\"periodSeconds\":%d,\"lastExecutionEpochSeconds\":%d,\"nextExecutionEpochSeconds\":%d}",
                RelayQueueCron.getInitialDelaySeconds(),
                RelayQueueCron.getPeriodSeconds(),
                RelayQueueCron.getLastExecutionEpochSeconds(),
                RelayQueueCron.getNextExecutionEpochSeconds());

        String queueJson = String.format("{\"size\":%d,\"retryHistogram\":%s}", queueSize, histogramJson);
        String schedulerJson = String.format("{\"config\":%s,\"cron\":%s}", schedulerConfigJson, cronJson);

        String response = String.format("{\"status\":\"UP\", \"uptime\":\"%s\", \"listeners\":%s, \"queue\":%s, \"scheduler\":%s}",
                uptimeString,
                listenersJson,
                queueJson,
                schedulerJson);

        sendResponse(exchange, 200, "application/json; charset=utf-8", response);
    }

    /**
     * Sends a successful HTTP response.
     *
     * @param exchange    The HTTP exchange object.
     * @param code        The HTTP status code.
     * @param contentType The content type of the response.
     * @param response    The response body as a string.
     * @throws IOException If an I/O error occurs.
     */
    private void sendResponse(HttpExchange exchange, int code, String contentType, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * Sends an error HTTP response.
     *
     * @param exchange The HTTP exchange object.
     * @param code     The HTTP error code.
     * @param message  The error message.
     * @throws IOException If an I/O error occurs.
     */
    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * Registers shutdown hooks to gracefully close resources.
     * <p>This ensures the HTTP server and metric registries are closed when the JVM terminates.
     */
    private void shutdownHooks() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) server.stop(0);
            if (jvmGcMetrics != null) jvmGcMetrics.close();
            if (prometheusRegistry != null) prometheusRegistry.close();
            if (graphiteRegistry != null) graphiteRegistry.close();
        }));
    }

    /**
     * Configures and creates a GraphiteMeterRegistry.
     *
     * @return A configured {@link GraphiteMeterRegistry} instance.
     */
    @NotNull
    private GraphiteMeterRegistry getGraphiteMeterRegistry() {
        GraphiteConfig graphiteConfig = new GraphiteConfig() {
            @Override
            public Duration step() {
                return Duration.ofSeconds(10);
            }

            @Override
            public String get(String key) {
                return null; // Accept defaults.
            }
        };
        return new GraphiteMeterRegistry(graphiteConfig, Clock.SYSTEM,
                (id, convention) -> id.getName().replaceAll("\\.", "_") + "." +
                        id.getTags().stream()
                                .map(t -> t.getKey().replaceAll("\\.", "_") + "-" + t.getValue().replaceAll("\\.", "_"))
                                .collect(Collectors.joining(".")));
    }

    /**
     * Generates a string representation of a full thread dump.
     *
     * @return A string containing the thread dump.
     */
    private String getThreadDump() {
        StringBuilder dump = new StringBuilder(32768);
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

        for (ThreadInfo threadInfo : threadInfos) {
            dump.append(String.format(
                    "\"%s\" #%d prio=%d state=%s%n",
                    threadInfo.getThreadName(),
                    threadInfo.getThreadId(),
                    // Thread priority is not available in ThreadInfo, default to 5
                    5,
                    threadInfo.getThreadState()
            ));
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                dump.append("   at ").append(stackTraceElement).append("\n");
            }
            dump.append("\n");
        }
        return dump.toString();
    }

    /**
     * Reads a resource file from the classpath into a string.
     *
     * @param path The path to the resource file.
     * @return The content of the file as a string.
     * @throws IOException If the resource is not found or cannot be read.
     */
    private String readResourceFile(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}

