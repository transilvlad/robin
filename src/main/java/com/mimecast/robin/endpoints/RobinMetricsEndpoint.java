package com.mimecast.robin.endpoints;

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
 * Extended metrics endpoint for Robin-specific statistics.
 *
 * <p>This class extends {@link MetricsEndpoint} to add Robin-specific metrics including:
 * <p>- SMTP listener thread pool statistics
 * <p>- Relay queue size and retry histogram
 * <p>- Retry scheduler configuration and cron execution stats
 * <p>- Metrics cron execution stats
 */
public class RobinMetricsEndpoint extends MetricsEndpoint {
    private static final Logger log = LogManager.getLogger(RobinMetricsEndpoint.class);

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
}

