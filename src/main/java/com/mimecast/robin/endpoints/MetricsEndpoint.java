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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
                    "/ui - Simple web UI for metrics visualization\n" +
                    "/metrics - Prometheus metrics scrape endpoint\n" +
                    "/graphite - Graphite formatted metrics for the UI\n";
            httpExchange.sendResponseHeaders(200, endpoints.getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(endpoints.getBytes());
            }
        });

        // Prometheus scrape endpoint.
        server.createContext("/metrics", httpExchange -> {
            String response = prometheusRegistry.scrape();
            httpExchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Simple web UI endpoint.
        server.createContext("/ui", httpExchange -> {
            String response = getHtmlContent();
            httpExchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            httpExchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Graphite data endpoint for the UI.
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

        // Ensure resources are closed on JVM shutdown.
        shutdownHooks(server, jvmGcMetrics, prometheusRegistry, graphiteRegistry);

        // Start the server in a new thread.
        new Thread(server::start).start();
        log.info("Prometheus metrics available at http://localhost:{}", Config.getServer().getMetricsPort());
        log.info("Metrics UI available at http://localhost:{}/ui", Config.getServer().getMetricsPort());
        log.info("Graphite data available at http://localhost:{}/graphite", Config.getServer().getMetricsPort());
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
     * Returns the HTML content for the metrics UI.
     *
     * @return HTML content as a String.
     */
    private static String getHtmlContent() {
        // In a real application, this would be loaded from a file.
        return "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<title>Metrics UI</title>" +
                "<style>body{font-family:sans-serif;display:grid;grid-template-columns:repeat(auto-fill,minmax(400px,1fr));gap:1rem;background:#f0f2f5;}h2{margin:0;padding:0.5rem;background:#e0e0e0;}canvas{padding:0.5rem;}</style>" +
                "<script src='https://cdn.jsdelivr.net/npm/chart.js'></script>" +
                "</head>" +
                "<body>" +
                "<script>" +
                "const metricsToChart = ['jvm_memory_used', 'jvm_memory_committed', 'jvm_threads_live', 'system_cpu_usage'];" +
                "const charts = {};" +
                "metricsToChart.forEach(metric => {" +
                "  const container = document.createElement('div');" +
                "  const title = document.createElement('h2');" +
                "  title.innerText = metric;" +
                "  const canvas = document.createElement('canvas');" +
                "  container.appendChild(title); container.appendChild(canvas);" +
                "  document.body.appendChild(container);" +
                "  const ctx = canvas.getContext('2d');" +
                "  charts[metric] = new Chart(ctx, {" +
                "    type: 'line'," +
                "    data: { labels: [], datasets: [{ label: metric, data: [], fill: false, borderColor: 'rgb(75, 192, 192)', tension: 0.1 }] }," +
                "    options: { animation: false }" +
                "  });" +
                "});" +
                "function fetchData() {" +
                "  fetch('/graphite')" +
                "    .then(response => response.text())" +
                "    .then(text => {" +
                "      const lines = text.trim().split('\\n');" +
                "      const now = new Date().toLocaleTimeString();" +
                "      lines.forEach(line => {" +
                "        const parts = line.split(' ');" +
                "        const metricName = parts[0].split('.')[0];" +
                "        if (metricsToChart.includes(metricName)) {" +
                "          const chart = charts[metricName];" +
                "          if (chart.data.labels.length > 20) {" +
                "            chart.data.labels.shift();" +
                "            chart.data.datasets[0].data.shift();" +
                "          }" +
                "          chart.data.labels.push(now);" +
                "          chart.data.datasets[0].data.push(parseFloat(parts[1]));" +
                "          chart.update();" +
                "        }" +
                "      });" +
                "    });" +
                "}" +
                "setInterval(fetchData, 2000);" +
                "fetchData();" +
                "</script>" +
                "</body>" +
                "</html>";
    }
}
