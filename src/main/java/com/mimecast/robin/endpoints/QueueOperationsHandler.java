package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.RelayQueueCron;
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
import java.util.List;
import java.util.Map;

/**
 * Handles queue-related HTTP operations for the API endpoint.
 * <p>This class encapsulates all queue management functionality including:
 * <ul>
 *   <li>Queue listing with pagination</li>
 *   <li>Queue item deletion</li>
 *   <li>Queue item retry</li>
 *   <li>Queue item bouncing</li>
 * </ul>
 */
public class QueueOperationsHandler {
    private static final Logger log = LogManager.getLogger(QueueOperationsHandler.class);
    private final Gson gson = new Gson();
    private final ApiEndpoint apiEndpoint;

    /**
     * Constructs a new QueueOperationsHandler.
     *
     * @param apiEndpoint The parent API endpoint for delegating common operations.
     */
    public QueueOperationsHandler(ApiEndpoint apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    /**
     * Handles <b>POST /client/queue/delete</b> requests.
     * <p>Deletes queue items by UID or UIDs.
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleDelete(HttpExchange exchange) throws IOException {
        if (!apiEndpoint.checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/delete from {}", exchange.getRemoteAddress());
        try {
            String body = apiEndpoint.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                apiEndpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                apiEndpoint.sendText(exchange, 400, "Invalid JSON body");
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
                apiEndpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("deletedCount", deletedCount);
            response.put("queueSize", queue.size());

            String json = gson.toJson(response);
            apiEndpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/delete: {}", e.getMessage(), e);
            apiEndpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue/retry</b> requests.
     * <p>Retries queue items by UID or UIDs (dequeue and re-enqueue with retry count bump).
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleRetry(HttpExchange exchange) throws IOException {
        if (!apiEndpoint.checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/retry from {}", exchange.getRemoteAddress());
        try {
            String body = apiEndpoint.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                apiEndpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                apiEndpoint.sendText(exchange, 400, "Invalid JSON body");
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
                apiEndpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
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
            apiEndpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/retry: {}", e.getMessage(), e);
            apiEndpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue/bounce</b> requests.
     * <p>Bounces queue items by UID or UIDs (remove and optionally generate bounce message).
     * <p>Accepts JSON body with either "uid" (single string) or "uids" (array of strings).
     */
    public void handleBounce(HttpExchange exchange) throws IOException {
        if (!apiEndpoint.checkMethodAndAuth(exchange, "POST")) {
            return;
        }

        log.info("POST /client/queue/bounce from {}", exchange.getRemoteAddress());
        try {
            String body = apiEndpoint.readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                apiEndpoint.sendText(exchange, 400, "Empty request body");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = gson.fromJson(body, Map.class);
            if (payload == null) {
                apiEndpoint.sendText(exchange, 400, "Invalid JSON body");
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
                apiEndpoint.sendText(exchange, 400, "Missing 'uid' or 'uids' parameter");
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
            apiEndpoint.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Error processing /client/queue/bounce: {}", e.getMessage(), e);
            apiEndpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }
}
