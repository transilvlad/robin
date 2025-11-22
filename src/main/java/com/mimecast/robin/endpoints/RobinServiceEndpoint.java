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
     * Generates JSON representation of bot processing thread pool statistics.
     *
     * @return JSON object string containing bot pool information.
     */
    private String getBotPoolJson() {
        java.util.concurrent.ExecutorService botExecutor = Server.getBotExecutor();
        if (botExecutor == null) {
            return "{\"enabled\":false}";
        }

        // For CachedThreadPool, we can get basic stats if it's a ThreadPoolExecutor
        if (botExecutor instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) botExecutor;
            return String.format("{\"enabled\":true,\"type\":\"cachedThreadPool\",\"poolSize\":%d,\"activeThreads\":%d,\"queueSize\":%d,\"taskCount\":%d,\"completedTaskCount\":%d}",
                    tpe.getPoolSize(),
                    tpe.getActiveCount(),
                    tpe.getQueue().size(),
                    tpe.getTaskCount(),
                    tpe.getCompletedTaskCount());
        }

        return "{\"enabled\":true,\"type\":\"unknown\"}";
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
            String propertiesJson = GSON.toJson(Config.getProperties().getMap());
            String serverJson = GSON.toJson(Config.getServer().getMap());
            String html = buildConfigViewerHtml(propertiesJson, serverJson);

            sendResponse(exchange, 200, "text/html; charset=utf-8", html);
            log.debug("Config viewer page served successfully");
        } catch (IOException e) {
            log.error("Failed to read config viewer template: {}", e.getMessage());
            sendResponse(exchange, 500, "text/plain; charset=utf-8", "Internal Server Error");
        } catch (Exception e) {
            log.error("Failed to generate config viewer: {}", e.getMessage());
            sendResponse(exchange, 500, "text/plain; charset=utf-8", "Internal Server Error");
        }
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

    /**
     * Handles requests for the application's health status with Robin-specific stats.
     * <p>Provides a JSON response with status, uptime, listeners, queue, scheduler, and metrics cron information.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    @Override
    protected void handleHealth(HttpExchange exchange) throws IOException {
        if (auth.isAuthenticated(exchange)) {
            log.debug("Handling /health: method={}, uri={}, remote={}",
                    exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

            Duration uptime = Duration.ofMillis(System.currentTimeMillis() - startTime);
            String uptimeString = String.format("%dd %dh %dm %ds",
                    uptime.toDays(),
                    uptime.toHoursPart(),
                    uptime.toMinutesPart(),
                    uptime.toSecondsPart());

            // Final health JSON response.
            String response = String.format("{\"status\":\"UP\", \"uptime\":\"%s\", \"listeners\":%s, \"queue\":%s, \"scheduler\":%s, \"metricsCron\":%s, \"botPool\":%s}",
                    uptimeString,
                    getListenersJson(), // Listener stats.
                    getQueueJson(), // Queue stats and retry histogram.
                    getSchedulerJson(), // Scheduler config and cron stats.
                    getMetricsCronJson(), // Metrics cron stats.
                    getBotPoolJson() // Bot processing thread pool stats.
            );

            sendResponse(exchange, 200, "application/json; charset=utf-8", response);
        } else {
            super.handleHealth(exchange);
        }
    }

    /**
     * Builds the HTML page for configuration viewer by loading from resource file and replacing placeholders.
     * Adds lazy-loaded external server configuration maps at the bottom.
     *
     * @param propertiesJson Properties configuration as JSON string.
     * @param serverJson     Server configuration as JSON string.
     * @return Complete HTML page.
     * @throws IOException If the resource file cannot be read.
     */
    private String buildConfigViewerHtml(String propertiesJson, String serverJson) throws IOException {
        String html = readResourceFile("config-viewer-ui.html");
        html = html.replace("{{PROPERTIES_CONFIG}}", escapeHtml(propertiesJson))
                   .replace("{{SERVER_CONFIG}}", escapeHtml(serverJson));

        StringBuilder extra = new StringBuilder();
        appendConfigSection(extra, "Storage Configuration", Config.getServer().getStorage().getMap());
        appendConfigSection(extra, "Queue Configuration", Config.getServer().getQueue().getMap());
        appendConfigSection(extra, "Relay Configuration", Config.getServer().getRelay().getMap());
        appendConfigSection(extra, "Dovecot Configuration", Config.getServer().getDovecot().getMap());
        appendConfigSection(extra, "Prometheus Configuration", Config.getServer().getPrometheus().getMap());
        appendConfigSection(extra, "Vault Configuration", Config.getServer().getVault().getMap());
        appendConfigSection(extra, "ClamAV Configuration", Config.getServer().getClamAV().getMap());
        appendConfigSection(extra, "Rspamd Configuration", Config.getServer().getRspamd().getMap());
        Map<String, ?> webhooks = Config.getServer().getWebhooks().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getMap()));
        appendConfigSection(extra, "Webhooks Configuration", webhooks);
        List<Map<String, Object>> users = Config.getServer().getUsers().stream().map(com.mimecast.robin.config.server.UserConfig::getMap).collect(Collectors.toList());
        appendConfigSection(extra, "Users Configuration", users);
        Map<String, ?> scenarios = Config.getServer().getScenarios().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getMap()));
        appendConfigSection(extra, "Scenarios Configuration", scenarios);

        html = html.replace("{{EXTRA_CONFIGS}}", extra.toString());
        return html;
    }

    /**
     * Appends a configuration section HTML if the data is not empty.
     *
     * @param sb    Accumulator for sections.
     * @param title Section title.
     * @param data  Map or List data object.
     */
    private void appendConfigSection(StringBuilder sb, String title, Object data) {
        if (data instanceof Map && ((Map<?, ?>) data).isEmpty()) return;
        if (data instanceof List && ((List<?>) data).isEmpty()) return;
        String json = GSON.toJson(data);
        sb.append("<div class=\"config-section\">")
          .append("<h2>").append(escapeHtml(title)).append("</h2>")
          .append("<pre>").append(escapeHtml(json)).append("</pre>")
          .append("</div>\n");
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
}

