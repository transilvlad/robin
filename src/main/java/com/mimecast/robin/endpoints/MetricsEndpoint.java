package com.mimecast.robin.endpoints;

import com.mimecast.robin.main.Config;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Monitoring endpoint.
 *
 * <p>Exposes Prometheus metrics over HTTP.
 */
public class MetricsEndpoint {
    private static final Logger log = LogManager.getLogger(MetricsEndpoint.class);

    /**
     * Starts the metrics endpoint.
     *
     * @throws IOException If an I/O error occurs.
     */
    public static void start() throws IOException {
        // Create a Prometheus registry.
        final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Bind the default JVM metrics to the registry.
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        final JvmGcMetrics jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);

        // Create and start a simple HTTP server.
        final HttpServer server = HttpServer.create(new InetSocketAddress(Config.getServer().getMetricsPort()), 10);
        server.createContext("/", httpExchange -> {
            String response = prometheusRegistry.scrape(); // Scrape the metrics.
            httpExchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = httpExchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Ensure resources are closed on JVM shutdown.
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
        }));

        new Thread(server::start).start();
        log.info("Prometheus metrics endpoint listening on port: {}", Config.getServer().getMetricsPort());
    }
}
