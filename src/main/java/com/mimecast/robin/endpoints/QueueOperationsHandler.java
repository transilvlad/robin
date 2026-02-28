package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Handles queue-related HTTP operations for the API endpoint.
 *
 * <p>This class encapsulates all queue management functionality including:
 * <ul>
 *   <li>Queue listing with pagination (/client/queue/list)</li>
 *   <li>Queue item deletion (/client/queue/delete)</li>
 *   <li>Queue item retry (/client/queue/retry)</li>
 *   <li>Queue item bouncing (/client/queue/bounce)</li>
 * </ul>
 */
public class QueueOperationsHandler {
    private static final Logger log = LogManager.getLogger(QueueOperationsHandler.class);
    private final Gson gson = new Gson();
    private final HttpEndpoint endpoint;
    private final HttpAuth auth;

    /**
     * Constructs a new QueueOperationsHandler.
     *
     * @param endpoint The parent HTTP endpoint for response utilities.
     * @param auth     The authentication handler.
     */
    public QueueOperationsHandler(HttpEndpoint endpoint, HttpAuth auth) {
        this.endpoint = endpoint;
        this.auth = auth;
    }

    /**
     * Checks if the request method matches expected and if the exchange is authenticated.
     *
     * @param exchange       HTTP exchange.
     * @param expectedMethod Expected HTTP method (e.g., "POST", "GET").
     * @return true if method and auth check pass, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    private boolean checkMethodAndAuth(HttpExchange exchange, String expectedMethod) throws IOException {
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-{} request: method={}", expectedMethod, exchange.getRequestMethod());
            endpoint.sendText(exchange, 405, "Method Not Allowed");
            return false;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return false;
        }

        return true;
    }

    /**
     * Handles <b>POST /client/queue/delete</b> requests.
     *
     * <p>Deletes queue items by UID or UIDs.
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleDelete(HttpExchange exchange) throws IOException {
        if (!checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/delete from {}", exchange.getRemoteAddress());
        try {
            String body = ApiEndpointUtils.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                endpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                endpoint.sendText(exchange, 400, "Invalid JSON body");
                return;
            }

            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
            int deletedCount = 0;

            // Handle single UID.
            if (payload.containsKey("uid")) {
                String uid = (String) payload.get("uid");
                if (queue.removeByUID(uid)) {
                    deletedCount = 1;
                    log.info("Deleted queue item with UID {}", uid);
                } else {
                    log.warn("Failed to delete queue item with UID {}", uid);
                }
            }
            // Handle multiple UIDs.
            else if (payload.containsKey("uids")) {
                @SuppressWarnings("unchecked")
                List<String> uids = (List<String>) payload.get("uids");
                deletedCount = queue.removeByUIDs(uids);
                log.info("Deleted {} queue items", deletedCount);
            } else {
                endpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("deletedCount", deletedCount);
            response.put("queueSize", queue.size());

            String json = gson.toJson(response);
            endpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/delete: {}", e.getMessage(), e);
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue/retry</b> requests.
     *
     * <p>Retries queue items by UID or UIDs (dequeue and re-enqueue with retry count bump).
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleRetry(HttpExchange exchange) throws IOException {
        if (!checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/retry from {}", exchange.getRemoteAddress());
        try {
            String body = ApiEndpointUtils.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                endpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                endpoint.sendText(exchange, 400, "Invalid JSON body");
                return;
            }

            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
            List<RelaySession> items = queue.snapshot();
            List<String> targetUIDs = new ArrayList<>();

            // Collect target UIDs.
            if (payload.containsKey("uid")) {
                targetUIDs.add((String) payload.get("uid"));
            } else if (payload.containsKey("uids")) {
                @SuppressWarnings("unchecked")
                List<String> uids = (List<String>) payload.get("uids");
                targetUIDs.addAll(uids);
            } else {
                endpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
                return;
            }

            // Collect items to retry and remove them from queue.
            List<RelaySession> toRetry = new ArrayList<>();
            for (RelaySession item : items) {
                if (targetUIDs.contains(item.getUID())) {
                    toRetry.add(item);
                }
            }

            // Remove items.
            int removedCount = queue.removeByUIDs(targetUIDs);

            // Re-enqueue with bumped retry count.
            for (RelaySession relaySession : toRetry) {
                relaySession.bumpRetryCount();
                queue.enqueue(relaySession);
            }

            log.info("Retried {} queue items", toRetry.size());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("retriedCount", toRetry.size());
            response.put("queueSize", queue.size());

            String json = gson.toJson(response);
            endpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/retry: {}", e.getMessage(), e);
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue/bounce</b> requests.
     *
     * <p>Bounces queue items by UID or UIDs (remove and optionally generate bounce message).
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleBounce(HttpExchange exchange) throws IOException {
        if (!checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/bounce from {}", exchange.getRemoteAddress());
        try {
            String body = ApiEndpointUtils.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                endpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                endpoint.sendText(exchange, 400, "Invalid JSON body");
                return;
            }

            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
            List<String> targetUIDs = new ArrayList<>();

            // Collect target UIDs.
            if (payload.containsKey("uid")) {
                targetUIDs.add((String) payload.get("uid"));
            } else if (payload.containsKey("uids")) {
                @SuppressWarnings("unchecked")
                List<String> uids = (List<String>) payload.get("uids");
                targetUIDs.addAll(uids);
            } else {
                endpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
                return;
            }

            // Remove items (bounce = delete in this context).
            // Future enhancement: generate actual bounce messages.
            int bouncedCount = queue.removeByUIDs(targetUIDs);
            log.info("Bounced {} queue items", bouncedCount);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("bouncedCount", bouncedCount);
            response.put("queueSize", queue.size());

            String json = gson.toJson(response);
            endpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/bounce: {}", e.getMessage(), e);
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>GET /client/queue/list</b> requests.
     *
     * <p>Lists the relay queue contents in a simple HTML table.
     * <p>Supports pagination via query parameters: page (1-based) and limit (default 50).
     */
    public void handleList(HttpExchange exchange) throws IOException {
        log.debug("GET /client/queue/list from {}", exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            // Parse pagination parameters.
            Map<String, String> queryParams = ApiEndpointUtils.parseQuery(exchange.getRequestURI());
            int page = 1;
            int limit = 50;

            try {
                if (queryParams.containsKey("page")) {
                    page = Math.max(1, Integer.parseInt(queryParams.get("page")));
                }
                if (queryParams.containsKey("limit")) {
                    limit = Math.max(1, Math.min(1000, Integer.parseInt(queryParams.get("limit"))));
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid pagination parameters: {}", e.getMessage());
            }

            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
            List<RelaySession> allItems = queue.snapshot();

            // Calculate pagination.
            int total = allItems.size();
            int startIndex = (page - 1) * limit;
            int endIndex = Math.min(startIndex + limit, total);
            int totalPages = (int) Math.ceil((double) total / limit);

            // Get page items.
            List<RelaySession> items = startIndex < total ? allItems.subList(startIndex, endIndex) : new ArrayList<>();

            // Load HTML template from resources.
            String template = endpoint.readResourceFile("queue-list-ui.html");

            // Build rows using helper method.
            StringBuilder rows = new StringBuilder(Math.max(8192, items.size() * 256));
            for (int i = 0; i < items.size(); i++) {
                rows.append(buildQueueRow(items.get(i), startIndex + i + 1));
            }

            // Build pagination controls using helper method.
            String pagination = buildPaginationControls(page, totalPages, limit);

            String html = template
                    .replace("{{TOTAL}}", String.valueOf(total))
                    .replace("{{PAGE}}", String.valueOf(page))
                    .replace("{{LIMIT}}", String.valueOf(limit))
                    .replace("{{TOTAL_PAGES}}", String.valueOf(totalPages))
                    .replace("{{SHOWING_FROM}}", String.valueOf(startIndex + 1))
                    .replace("{{SHOWING_TO}}", String.valueOf(endIndex))
                    .replace("{{PAGINATION}}", pagination)
                    .replace("{{ROWS}}", rows.toString());

            endpoint.sendHtml(exchange, 200, html);
        } catch (Exception e) {
            log.error("Error processing /client/queue/list: {}", e.getMessage());
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Builds an HTML row for a relay session in the queue list.
     */
    private String buildQueueRow(RelaySession relaySession, int rowNumber) {
        Session session = relaySession.getSession();
        List<MessageEnvelope> envs = session != null ? session.getEnvelopes() : null;
        int envCount = envs != null ? envs.size() : 0;

        // Recipients summary (first 5 unique, then +N).
        StringBuilder recipients = new StringBuilder();
        int added = 0;
        HashSet<String> seen = new HashSet<>();
        if (envs != null) {
            for (MessageEnvelope env : envs) {
                if (env == null) continue;
                for (String r : env.getRcpts()) {
                    if (seen.add(r)) {
                        if (added > 0) recipients.append(", ");
                        recipients.append(ApiEndpointUtils.escapeHtml(r));
                        added++;
                        if (added >= 5) break;
                    }
                }
                if (added >= 5) break;
            }
        }
        if (envs != null) {
            int totalRecipients = envs.stream().mapToInt(e -> e.getRcpts() != null ? e.getRcpts().size() : 0).sum();
            if (totalRecipients > added) {
                recipients.append(" … (+").append(totalRecipients - added).append(")");
            }
        }

        // Files summary (first 5 base names with tooltip of full path).
        StringBuilder files = new StringBuilder();
        int fadded = 0;
        if (envs != null) {
            for (MessageEnvelope env : envs) {
                if (env == null) continue;
                String f = env.getFile();
                if (f != null && !f.isBlank()) {
                    String base = Paths.get(f).getFileName().toString();
                    if (fadded > 0) files.append(", ");
                    files.append("<span title='").append(ApiEndpointUtils.escapeHtml(f)).append("'>")
                            .append(ApiEndpointUtils.escapeHtml(base)).append("</span>");
                    fadded++;
                    if (fadded >= 5) break;
                }
            }
        }

        String lastRetry = relaySession.getLastRetryTime() > 0 ? relaySession.getLastRetryDate() : "-";
        String sessionUID = session != null ? session.getUID() : "-";
        String relayUID = relaySession.getUID();

        // Load row template.
        String rowTemplate;
        try {
            rowTemplate = endpoint.readResourceFile("queue-list-row.html");
        } catch (IOException e) {
            log.error("Failed to load queue-list-row.html: {}", e.getMessage());
            // Fallback to inline template.
            rowTemplate = "<tr>" +
                    "<td class='checkbox-col'><input type='checkbox' class='row-checkbox' data-uid='{{RELAY_UID}}'/></td>" +
                    "<td class='nowrap'>{{ROW_NUMBER}}</td>" +
                    "<td class='mono'>{{SESSION_UID}}</td>" +
                    "<td>{{DATE}}</td>" +
                    "<td>{{PROTOCOL}}</td>" +
                    "<td>{{RETRY_COUNT}}</td>" +
                    "<td class='nowrap'>{{LAST_RETRY}}</td>" +
                    "<td>{{ENVELOPES}}</td>" +
                    "<td>{{RECIPIENTS}}</td>" +
                    "<td>{{FILES}}</td>" +
                    "<td class='actions nowrap'>" +
                    "<button class='btn-delete' data-uid='{{RELAY_UID}}'>Delete</button> " +
                    "<button class='btn-retry' data-uid='{{RELAY_UID}}'>Retry</button> " +
                    "<button class='btn-bounce' data-uid='{{RELAY_UID}}'>Bounce</button>" +
                    "</td>" +
                    "</tr>";
        }

        return rowTemplate
                .replace("{{RELAY_UID}}", ApiEndpointUtils.escapeHtml(relayUID))
                .replace("{{ROW_NUMBER}}", String.valueOf(rowNumber))
                .replace("{{SESSION_UID}}", ApiEndpointUtils.escapeHtml(sessionUID))
                .replace("{{DATE}}", ApiEndpointUtils.escapeHtml(session.getDate()))
                .replace("{{PROTOCOL}}", ApiEndpointUtils.escapeHtml(relaySession.getProtocol()))
                .replace("{{RETRY_COUNT}}", String.valueOf(relaySession.getRetryCount()))
                .replace("{{LAST_RETRY}}", ApiEndpointUtils.escapeHtml(lastRetry))
                .replace("{{ENVELOPES}}", String.valueOf(envCount))
                .replace("{{RECIPIENTS}}", recipients.toString())
                .replace("{{FILES}}", files.toString());
    }

    /**
     * Builds pagination controls HTML.
     */
    private String buildPaginationControls(int currentPage, int totalPages, int limit) {
        if (totalPages <= 1) {
            return "";
        }

        StringBuilder pagination = new StringBuilder();
        pagination.append("<div class='pagination'>");

        // Previous button.
        if (currentPage > 1) {
            pagination.append("<a href='?page=").append(currentPage - 1).append("&limit=").append(limit).append("'>&laquo; Previous</a> ");
        } else {
            pagination.append("<span class='disabled'>&laquo; Previous</span> ");
        }

        // Page numbers (show up to 9 pages around current).
        int startPage = Math.max(1, currentPage - 4);
        int endPage = Math.min(totalPages, currentPage + 4);

        if (startPage > 1) {
            pagination.append("<a href='?page=1&limit=").append(limit).append("'>1</a> ");
            if (startPage > 2) {
                pagination.append("<span>...</span> ");
            }
        }

        for (int p = startPage; p <= endPage; p++) {
            if (p == currentPage) {
                pagination.append("<span class='current'>").append(p).append("</span> ");
            } else {
                pagination.append("<a href='?page=").append(p).append("&limit=").append(limit).append("'>").append(p).append("</a> ");
            }
        }

        if (endPage < totalPages) {
            if (endPage < totalPages - 1) {
                pagination.append("<span>...</span> ");
            }
            pagination.append("<a href='?page=").append(totalPages).append("&limit=").append(limit).append("'>").append(totalPages).append("</a> ");
        }

        // Next button.
        if (currentPage < totalPages) {
            pagination.append("<a href='?page=").append(currentPage + 1).append("&limit=").append(limit).append("'>Next &raquo;</a>");
        } else {
            pagination.append("<span class='disabled'>Next &raquo;</span>");
        }

        pagination.append("</div>");
        return pagination.toString();
    }
}
