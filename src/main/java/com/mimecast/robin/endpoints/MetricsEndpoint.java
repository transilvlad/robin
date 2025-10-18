package com.mimecast.robin.endpoints;

import com.mimecast.robin.main.Config;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Monitoring endpoint.
 *
 * <p>Exposes Prometheus metrics over HTTP.
 * <p>Exposes a simple Web UI for metrics visualization.
 */
public class MetricsEndpoint {
    private static final Logger log = LogManager.getLogger(MetricsEndpoint.class);

    /**
     * Starts the metrics endpoint.
     *
     * @throws IOException If an I/O error occurs.
     */
    public static void start() throws IOException {
        // Prometheus registry for scraping.
        final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Graphite registry for the UI.
        final GraphiteMeterRegistry graphiteRegistry = getGraphiteMeterRegistry();

        // Bind the default JVM metrics to the registries.
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        new JvmMemoryMetrics().bindTo(graphiteRegistry);
        final JvmGcMetrics jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(prometheusRegistry);
        jvmGcMetrics.bindTo(graphiteRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(graphiteRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(graphiteRegistry);

        // Create and start a simple HTTP server.
        final HttpServer server = HttpServer.create(new InetSocketAddress(Config.getServer().getMetricsPort()), 10);

        // Landing page with available endpoints.
        server.createContext("/", httpExchange -> {
            String endpoints = "Available endpoints:\n" +
                    "/metrics - Metrics UI\n" +
                    "/graphite - Graphite data endpoint\n" +
                    "/prometheus - Prometheus data endpoint\n" +
                    "/threads - Thread dump endpoint\n";
            httpExchange.sendResponseHeaders(200, endpoints.getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(endpoints.getBytes());
            }
        });

        // Simple web UI for Graphite.
        server.createContext("/metrics", httpExchange -> {
            try {
                String response = readResourceFile("metrics-ui.html");
                httpExchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                httpExchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                log.error("Could not read graphite-ui.html", e);
                String errorResponse = "500 - Internal Server Error";
                httpExchange.sendResponseHeaders(500, errorResponse.length());
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(errorResponse.getBytes());
                }
            }
        });

        // Graphite data endpoint.
        server.createContext("/graphite", httpExchange -> {
            StringBuilder response = new StringBuilder();
            graphiteRegistry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> {
                String name = meter.getId().getName().replaceAll("\\.", "_");
                response.append(name).append(" ").append(measurement.getValue()).append(" ").append(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())).append("\n");
            }));
            httpExchange.sendResponseHeaders(200, response.toString().getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.toString().getBytes());
            }
        });

        // Prometheus data endpoint.
        server.createContext("/prometheus", httpExchange -> {
            String response = prometheusRegistry.scrape();
            httpExchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Thread dump endpoint.
        server.createContext("/threads", httpExchange -> {
            String response = getThreadDump();
            httpExchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            httpExchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Ensure resources are closed on JVM shutdown.
        shutdownHooks(server, jvmGcMetrics, prometheusRegistry, graphiteRegistry);

        // Start the server in a new thread.
        new Thread(server::start).start();
        log.info("UI available at http://localhost:{}/metrics", Config.getServer().getMetricsPort());
        log.info("Graphite data available at http://localhost:{}/graphite", Config.getServer().getMetricsPort());
        log.info("Prometheus data available at http://localhost:{}/prometheus", Config.getServer().getMetricsPort());
        log.info("Threads dump available at http://localhost:{}/threads", Config.getServer().getMetricsPort());
    }

    private static void shutdownHooks(HttpServer server, JvmGcMetrics jvmGcMetrics, PrometheusMeterRegistry prometheusRegistry, GraphiteMeterRegistry graphiteRegistry) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(0);
            } catch (Exception ignored) {
            }
            try {
                jvmGcMetrics.close();
            } catch (Exception e) {
                log.warn("Failed to close JvmGcMetrics: {}", e.getMessage());
            }
            try {
                prometheusRegistry.close();
            } catch (Exception e) {
                log.warn("Failed to close PrometheusMeterRegistry: {}", e.getMessage());
            }
            try {
                graphiteRegistry.close();
            } catch (Exception e) {
                log.warn("Failed to close GraphiteMeterRegistry: {}", e.getMessage());
            }
        }));
    }

    /**
     * Creates and configures a GraphiteMeterRegistry.
     *
     * @return Configured GraphiteMeterRegistry.
     */
    @NotNull
    private static GraphiteMeterRegistry getGraphiteMeterRegistry() {
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
        final GraphiteMeterRegistry graphiteRegistry = new GraphiteMeterRegistry(graphiteConfig, Clock.SYSTEM,
                (id, convention) -> id.getName().replaceAll("\\.", "_") + "." +
                        id.getTags().stream()
                                .map(t -> t.getKey().replaceAll("\\.", "_") + "-" + t.getValue().replaceAll("\\.", "_"))
                                .collect(Collectors.joining(".")));
        return graphiteRegistry;
    }

    /**
     * Returns a string representation of all thread stack traces.
     *
     * @return Thread dump as a String.
     */
    private static String getThreadDump() {
        StringBuilder dump = new StringBuilder();
        Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();

        for (Map.Entry<Thread, StackTraceElement[]> entry : stackTraces.entrySet()) {
            Thread thread = entry.getKey();
            dump.append(String.format(
                    "\"%s\" #%d prio=%d state=%s%n",
                    thread.getName(),
                    thread.getId(),
                    thread.getPriority(),
                    thread.getState()
            ));
            for (StackTraceElement stackTraceElement : entry.getValue()) {
                dump.append("   at ").append(stackTraceElement).append("\n");
            }
            dump.append("\n");
        }
        return dump.toString();
    }

    /**
     * Reads a resource file from the classpath.
     *
     * @param path The path to the resource file.
     * @return The content of the file as a String.
     * @throws IOException If the file cannot be read.
     */
    private static String readResourceFile(String path) throws IOException {
        try (InputStream is = MetricsEndpoint.class.getClassLoader().getResourceAsStream(path)) {
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