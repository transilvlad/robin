package com.mimecast.robin.endpoints;

import com.mimecast.robin.metrics.MetricsRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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

    protected HttpServer server;
    private PrometheusMeterRegistry prometheusRegistry;
    private GraphiteMeterRegistry graphiteRegistry;
    private JvmGcMetrics jvmGcMetrics;
    protected final long startTime = System.currentTimeMillis();
    protected HttpBasicAuth auth;

    /**
     * Starts the embedded HTTP server for the metrics and management endpoint.
     * <p>This method initializes metric registries, binds JVM metrics, creates HTTP contexts for all endpoints,
     * and sets up shutdown hooks for graceful termination.
     *
     * @param metricsPort The port on which the HTTP server will listen for incoming requests.
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start(int metricsPort) throws IOException {
        start(metricsPort, null, null);
    }

    /**
     * Starts the embedded HTTP server for the metrics and management endpoint with authentication.
     * <p>This method initializes metric registries, binds JVM metrics, creates HTTP contexts for all endpoints,
     * and sets up shutdown hooks for graceful termination.
     *
     * @param metricsPort The port on which the HTTP server will listen for incoming requests.
     * @param username The username for HTTP Basic Authentication (null to disable authentication).
     * @param password The password for HTTP Basic Authentication.
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start(int metricsPort, String username, String password) throws IOException {
        this.auth = new HttpBasicAuth(username, password, "Metrics Endpoint");

        prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        graphiteRegistry = getGraphiteMeterRegistry();
        MetricsRegistry.register(prometheusRegistry, graphiteRegistry);
        bindJvmMetrics();

        server = HttpServer.create(new InetSocketAddress(metricsPort), 10);
        createContexts();
        shutdownHooks();

        new Thread(server::start).start();
        log.info("Landing available at http://localhost:{}/", metricsPort);
        log.info("UI available at http://localhost:{}/metrics", metricsPort);
        log.info("Graphite data available at http://localhost:{}/graphite", metricsPort);
        log.info("Prometheus data available at http://localhost:{}/prometheus", metricsPort);
        log.info("Environment variable available at http://localhost:{}/env", metricsPort);
        log.info("System properties available at http://localhost:{}/sysprops", metricsPort);
        log.info("Threads dump available at http://localhost:{}/threads", metricsPort);
        log.info("Heap dump available at http://localhost:{}/heapdump", metricsPort);
        log.info("Health available at http://localhost:{}/health", metricsPort);
        if (auth.isAuthEnabled()) {
            log.info("HTTP Basic Authentication is enabled for metrics endpoint");
        }
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
    protected void createContexts() {
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
        log.debug("Handling metrics landing page: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /metrics UI: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.trace("Handling /graphite: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /prometheus: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /env: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /sysprops: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /threads: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
        log.debug("Handling /heapdump: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }
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
     * <p>Provides a JSON response with the status and uptime.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    protected void handleHealth(HttpExchange exchange) throws IOException {
        log.debug("Handling /health: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        Duration uptime = Duration.ofMillis(System.currentTimeMillis() - startTime);
        String uptimeString = String.format("%dd %dh %dm %ds",
                uptime.toDays(),
                uptime.toHoursPart(),
                uptime.toMinutesPart(),
                uptime.toSecondsPart());

        // Final health JSON response.
        String response = String.format("{\"status\":\"UP\", \"uptime\":\"%s\"}",
                uptimeString);

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
    protected void sendResponse(HttpExchange exchange, int code, String contentType, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
        log.trace("Sent response: status={}, contentType={}, bytes={}", code, contentType, responseBytes.length);
    }

    /**
     * Sends an error HTTP response.
     *
     * @param exchange The HTTP exchange object.
     * @param code     The HTTP error code.
     * @param message  The error message.
     * @throws IOException If an I/O error occurs.
     */
    protected void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
        log.debug("Sent error response: status={}, bytes={}", code, responseBytes.length);
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

