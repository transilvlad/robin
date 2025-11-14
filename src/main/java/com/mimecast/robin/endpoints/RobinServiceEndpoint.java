package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.metrics.MetricsCron;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RetryScheduler;
import com.mimecast.robin.smtp.SmtpListener;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extended service endpoint for Robin-specific statistics.
 *
 * <p>This class extends {@link ServiceEndpoint} to add Robin-specific metrics including:
 * <p>- SMTP listener thread pool statistics
 * <p>- Relay queue size and retry histogram
 * <p>- Retry scheduler configuration and cron execution stats
 * <p>- Metrics cron execution stats
 * <p>- Configuration reload via HTTP API endpoint
 */
public class RobinServiceEndpoint extends ServiceEndpoint {
    private static final Logger log = LogManager.getLogger(RobinServiceEndpoint.class);

    private static final Object CONFIG_RELOAD_LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Overrides createContexts to add config endpoints at the top in landing page order.
     * Organizes endpoints into logical groups: config, metrics, and system.
     */
    @Override
    protected void createContexts() {
        int port = server.getAddress().getPort();

        // Landing page.
        server.createContext("/", this::handleLandingPage);
        log.info("Landing available at http://localhost:{}/", port);

        // Config endpoints at the top (matching landing page order).
        server.createContext("/config", this::handleConfigViewer);
        log.info("Config viewer available at http://localhost:{}/config", port);

        server.createContext("/config/reload", this::handleConfigReload);
        log.info("Config reload available at http://localhost:{}/config/reload", port);

        // System endpoints grouped under /system
        server.createContext("/system/env", this::handleEnv);
        log.info("Environment variables available at http://localhost:{}/system/env", port);

        server.createContext("/system/props", this::handleSysProps);
        log.info("System properties available at http://localhost:{}/system/props", port);

        server.createContext("/system/threads", this::handleThreads);
        log.info("Thread dump available at http://localhost:{}/system/threads", port);

        server.createContext("/system/heapdump", this::handleHeapDump);
        log.info("Heap dump available at http://localhost:{}/system/heapdump", port);

        // Metrics endpoints grouped under /metrics
        server.createContext("/metrics", this::handleMetricsUi);
        log.info("Metrics UI available at http://localhost:{}/metrics", port);

        server.createContext("/metrics/graphite", this::handleGraphite);
        log.info("Graphite metrics available at http://localhost:{}/metrics/graphite", port);

        server.createContext("/metrics/prometheus", this::handlePrometheus);
        log.info("Prometheus metrics available at http://localhost:{}/metrics/prometheus", port);

        // Health endpoint last
        server.createContext("/health", this::handleHealth);
        log.info("Health available at http://localhost:{}/health", port);
    }

    /**
     * Handles requests for the application's health status with Robin-specific stats.
     * <p>Provides a JSON response with status, uptime, listeners, queue, scheduler, and metrics cron information.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    @Override
    protected void handleHealth(HttpExchange exchange) throws IOException {
        if (!auth.isAuthenticated(exchange)) {
            log.debug("Handling /health: method={}, uri={}, remote={}",
                    exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

            Duration uptime = Duration.ofMillis(System.currentTimeMillis() - startTime);
            String uptimeString = String.format("%dd %dh %dm %ds",
                    uptime.toDays(),
                    uptime.toHoursPart(),
                    uptime.toMinutesPart(),
                    uptime.toSecondsPart());

            // Final health JSON response.
            String response = String.format("{\"status\":\"UP\", \"uptime\":\"%s\", \"listeners\":%s, \"queue\":%s, \"scheduler\":%s, \"metricsCron\":%s}",
                    uptimeString,
                    getListenersJson(), // Listener stats.
                    getQueueJson(), // Queue stats and retry histogram.
                    getSchedulerJson(), // Scheduler config and cron stats.
                    getMetricsCronJson() // Metrics cron stats.
            );

            sendResponse(exchange, 200, "application/json; charset=utf-8", response);
        } else {
            super.handleHealth(exchange);
        }
    }

    /**
     * Generates JSON representation of SMTP listener statistics.
     *
     * @return JSON array string containing listener thread pool information.
     */
    private String getListenersJson() {
        List<SmtpListener> listeners = Server.getListeners();
        return listeners.stream()
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
    }

    /**
     * Generates JSON representation of relay queue statistics.
     *
     * @return JSON object string containing queue size and retry histogram.
     */
    private String getQueueJson() {
        long queueSize = RelayQueueCron.getQueueSize();
        Map<Integer, Long> histogram = RelayQueueCron.getRetryHistogram();
        String histogramJson = histogram.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> String.format("\"%d\":%d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        return String.format("{\"size\":%d,\"retryHistogram\":%s}", queueSize, histogramJson);
    }

    /**
     * Generates JSON representation of retry scheduler configuration and cron statistics.
     *
     * @return JSON object string containing scheduler config and cron execution info.
     */
    private String getSchedulerJson() {
        String schedulerConfigJson = String.format("{\"totalRetries\":%d,\"firstWaitMinutes\":%d,\"growthFactor\":%.2f}",
                RetryScheduler.getTotalRetries(),
                RetryScheduler.getFirstWaitMinutes(),
                RetryScheduler.getGrowthFactor());

        String cronJson = String.format("{\"initialDelaySeconds\":%d,\"periodSeconds\":%d,\"lastExecutionEpochSeconds\":%d,\"nextExecutionEpochSeconds\":%d}",
                RelayQueueCron.getInitialDelaySeconds(),
                RelayQueueCron.getPeriodSeconds(),
                RelayQueueCron.getLastExecutionEpochSeconds(),
                RelayQueueCron.getNextExecutionEpochSeconds());

        return String.format("{\"config\":%s,\"cron\":%s}", schedulerConfigJson, cronJson);
    }

    /**
     * Generates JSON representation of metrics cron execution statistics.
     *
     * @return JSON object string containing metrics cron execution info.
     */
    private String getMetricsCronJson() {
        return String.format("{\"intervalSeconds\":%d,\"lastExecutionEpochSeconds\":%d,\"nextExecutionEpochSeconds\":%d}",
                MetricsCron.getIntervalSeconds(),
                MetricsCron.getLastExecutionEpochSeconds(),
                MetricsCron.getNextExecutionEpochSeconds());
    }

    /**
     * Handles GET requests to display configuration viewer UI.
     * Shows properties and server configuration in formatted JSON with reload button.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleConfigViewer(HttpExchange exchange) throws IOException {
        log.debug("Handling /config: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            // Get current configurations as Maps and format as JSON
            String propertiesJson = GSON.toJson(Config.getProperties().getMap());
            String serverJson = GSON.toJson(Config.getServer().getMap());

            // Build HTML response directly
            String html = buildConfigViewerHtml(propertiesJson, serverJson);

            sendResponse(exchange, 200, "text/html; charset=utf-8", html);
            log.debug("Config viewer page served successfully");
        } catch (Exception e) {
            log.error("Failed to generate config viewer: {}", e.getMessage());
            sendResponse(exchange, 500, "text/plain; charset=utf-8", "Internal Server Error");
        }
    }

    /**
     * Builds the HTML page for configuration viewer.
     *
     * @param propertiesJson Properties configuration as JSON string.
     * @param serverJson     Server configuration as JSON string.
     * @return Complete HTML page.
     */
    private String buildConfigViewerHtml(String propertiesJson, String serverJson) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "    <title>Configuration Viewer</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, Helvetica, sans-serif; margin: 20px; background-color: #fdfdfd; color: #333; }\n" +
                "        .header { display: flex; align-items: center; gap: 12px; margin-bottom: 1em; }\n" +
                "        .logo { width: 45px; height: 45px; }\n" +
                "        h1 { font-size: 1.4rem; margin: 0; }\n" +
                "        h2 { font-size: 1.2rem; margin: 20px 0 10px 0; color: #555; }\n" +
                "        .actions { margin: 20px 0; }\n" +
                "        .actions button { padding: 10px 20px; border: 1px solid #ccc; background: #f9f9f9; cursor: pointer; border-radius: 4px; font-size: 14px; font-weight: bold; }\n" +
                "        .actions button:hover { background: #FE8502; color: white; }\n" +
                "        .actions button:active { background: #44A83A; color: white; }\n" +
                "        .actions button:disabled { background: #ddd; color: #999; cursor: not-allowed; }\n" +
                "        .config-section { background: #fff; border: 1px solid #ddd; border-radius: 4px; margin-bottom: 20px; padding: 15px; }\n" +
                "        pre { background: #f5f5f5; border: 1px solid #ddd; border-radius: 4px; padding: 15px; overflow-x: auto; font-family: 'Courier New', Courier, monospace; font-size: 13px; line-height: 1.4; margin: 0; }\n" +
                "        .message { padding: 12px; margin: 10px 0; border-radius: 4px; display: none; }\n" +
                "        .message.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; }\n" +
                "        .message.error { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; }\n" +
                "        .message.info { background: #d1ecf1; border: 1px solid #bee5eb; color: #0c5460; }\n" +
                "        .spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid #f3f3f3; border-top: 2px solid #FE8502; border-radius: 50%; animation: spin 1s linear infinite; margin-left: 8px; vertical-align: middle; }\n" +
                "        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<header class=\"header\">\n" +
                "    <svg class=\"logo\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 600 600\" width=\"45\" height=\"45\" role=\"img\" aria-label=\"Robin logo\">\n" +
                "      <title>Robin logo</title>\n" +
                "      <path fill=\"#424146\" d=\"m433 51 11 14c2 3 3 6 6 8l9 1c25 2 46 16 63 34l1 4-1 2 1 2-1 6c-7 6-16 7-25 6l-16-2c-18-4-18-4-36 0l-15 14-2 2a56 56 0 0 0-16 43c0 11 6 20 14 27 4 3 8 4 12 5 10 2 16 6 22 14 4 7 4 17 3 25l-3 8-1 4a174 174 0 0 1-41 66l-7 7c-2 4-5 6-9 9l-6 6a260 260 0 0 1-26 21c-15 12-31 22-48 32l-37 24-2 2-33 22-16 9 4-16 1-2c7-15 16-29 27-42l3-5h-2l-9-2c-3-3-3-7-3-11l-2 1-3 1-3 2c-11 4-24 2-35 1-23-4-46-1-69 5l-16 4-14 4-3 1-45 12-3 1-7 2-3 1-9 3-7 1c0-7 6-11 10-15l-5-2 1-4a1497 1497 0 0 1 75-17l3-1a563 563 0 0 0 42-11c64-18 64-18 114-59a361 361 0 0 1 27-35 697 697 0 0 1 25-31c7-11 16-21 24-31l1-2c16-18 16-18 25-25l7-10 1-2 8-17c8-20 18-38 36-51l-1-2c-3-5-5-10-4-16l2-2h3v-3c0-6 0-11 3-17l-2-4-3-3-5-5v-1l9-1 1-9Z\"/>\n" +
                "      <path fill=\"#FE8502\" d=\"M525 121c5 3 7 7 9 12v9h-12l-6 6c-4 3-7 5-12 6v1c14 1 14 1 27-3l7-1-2-3-2-5 4 1c8 3 16 5 25 6l-3 3h-2l-2 1-3 1-3 1-15 4v17c0 9 0 17 5 25a100 100 0 0 1 14 40c5 43-11 93-37 126a154 154 0 0 1-118 57h-3l-10 1a3658 3658 0 0 0-22 1 1964753589 1964753589 0 0 1 40 52l1 2 2 2 2 2 2 4 4 5 15 1c16 0 16 0 23 2v1l-19 1 7 1 18 5 2 1 10 5c-3 2-6 0-9-1-12-3-12-3-25-4l2 1c7 4 12 9 18 15l-1 2-3-2c-19-11-37-17-59-19 2-3 5-4 9-5l3-1 2-1-2-3a886 886 0 0 1-46-54l-9-10a2242 2242 0 0 0 42 81l13 1c8 0 17 5 24 9h-8c-4-2-8-2-12-2l2 1c9 6 17 11 22 21-4-1-8-4-11-6-7-5-14-8-21-11l3 3c4 4 6 9 8 15l-1 2-3-3a80 80 0 0 0-50-26c3-2 7-3 10-3l4-1h9l-20-38-16-30-1-2a2176 2176 0 0 1-7-12l-1-4h-2c-7-2-12-5-18-9h8l4 1 3 1a191 191 0 0 0 37 5c12 1 21-2 31-7l3-1a189 189 0 0 0 84-74c15-26 25-62 17-92l-4-9-1-3c-10-20-30-32-49-42-7-4-11-9-14-16l-1-11v-2c0-8 1-14 6-20l2-1 1 3c1 5 2 6 6 8 4 1 8 1 12-1 3-3 4-4 4-8 0-5-3-7-6-10h13l14 1c10 1 18 0 28-2l2-1c3-1 5-2 7-5l1-4v-4Z\"/>\n" +
                "    </svg>\n" +
                "    <h1>Configuration Viewer</h1>\n" +
                "</header>\n" +
                "<div class=\"actions\">\n" +
                "    <button id=\"reloadBtn\" onclick=\"reloadConfig()\">Reload Configuration</button>\n" +
                "</div>\n" +
                "<div id=\"message\" class=\"message\"></div>\n" +
                "<div class=\"config-section\">\n" +
                "    <h2>Properties Configuration</h2>\n" +
                "    <pre>" + escapeHtml(propertiesJson) + "</pre>\n" +
                "</div>\n" +
                "<div class=\"config-section\">\n" +
                "    <h2>Server Configuration</h2>\n" +
                "    <pre>" + escapeHtml(serverJson) + "</pre>\n" +
                "</div>\n" +
                "<script>\n" +
                "function showMessage(text, type) {\n" +
                "    const msgDiv = document.getElementById('message');\n" +
                "    msgDiv.textContent = text;\n" +
                "    msgDiv.className = 'message ' + type;\n" +
                "    msgDiv.style.display = 'block';\n" +
                "    setTimeout(() => { msgDiv.style.display = 'none'; }, 5000);\n" +
                "}\n" +
                "function reloadConfig() {\n" +
                "    const btn = document.getElementById('reloadBtn');\n" +
                "    const originalText = btn.textContent;\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerHTML = 'Reloading...<span class=\"spinner\"></span>';\n" +
                "    fetch('/config/reload', { method: 'POST', headers: { 'Content-Type': 'application/json' } })\n" +
                "    .then(response => response.json())\n" +
                "    .then(data => {\n" +
                "        if (data.status === 'OK') {\n" +
                "            showMessage('Configuration reloaded successfully! Refreshing page...', 'success');\n" +
                "            setTimeout(() => { window.location.reload(); }, 1500);\n" +
                "        } else {\n" +
                "            showMessage('Failed to reload: ' + (data.message || 'Unknown error'), 'error');\n" +
                "            btn.disabled = false; btn.textContent = originalText;\n" +
                "        }\n" +
                "    })\n" +
                "    .catch(error => {\n" +
                "        showMessage('Error: ' + error.message, 'error');\n" +
                "        btn.disabled = false; btn.textContent = originalText;\n" +
                "    });\n" +
                "}\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * Escapes HTML special characters to prevent XSS.
     *
     * @param text The text to escape.
     * @return The escaped text.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Handles POST requests to reload server configuration.
     * Thread-safe using synchronized block to serialize reload operations.
     * Configuration changes apply immediately without server restart.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleConfigReload(HttpExchange exchange) throws IOException {
        log.debug("Handling /config/reload: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            synchronized (CONFIG_RELOAD_LOCK) {
                log.info("Configuration reload triggered via API");
                Config.triggerReload();
                log.info("Configuration reloaded successfully");
            }

            String response = "{\"status\":\"OK\", \"message\":\"Configuration reloaded successfully\"}";
            sendResponse(exchange, 200, "application/json; charset=utf-8", response);
        } catch (Exception e) {
            log.error("Failed to reload configuration: {}", e.getMessage());
            String errorResponse = String.format("{\"status\":\"ERROR\", \"message\":\"Failed to reload configuration: %s\"}", e.getMessage());
            sendResponse(exchange, 500, "application/json; charset=utf-8", errorResponse);
        }
    }
}

