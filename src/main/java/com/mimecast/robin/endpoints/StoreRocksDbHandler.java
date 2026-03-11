package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStore;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStoreManager;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JSON-only handler for the RocksDB mailbox API.
 */
public class StoreRocksDbHandler implements ApiHandler {
    private static final Logger log = LogManager.getLogger(StoreRocksDbHandler.class);
    private static final String PATH = "/store-rocksdb";

    private final HttpEndpoint endpoint;
    private final HttpAuth auth;
    private final Gson gson = ApiEndpointUtils.getGson();

    public StoreRocksDbHandler(HttpEndpoint endpoint, HttpAuth auth) {
        this.endpoint = endpoint;
        this.auth = auth;
    }

    @Override
    public String getPath() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        if (!RocksDbMailboxStoreManager.isEnabled()) {
            endpoint.sendJson(exchange, 503, "{\"error\":\"RocksDB storage disabled.\"}");
            return;
        }

        String decoded = parsePath(exchange.getRequestURI().getPath());
        if (decoded.contains("..")) {
            endpoint.sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
            return;
        }
        List<String> segments = decoded.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.stream(decoded.split("/")).filter(part -> part != null && !part.isBlank()).toList());

        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGet(exchange, segments);
            } else {
                handleMutation(exchange, segments);
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                endpoint.sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            endpoint.sendJson(exchange, 400, "{\"r\":0,\"msg\":\"" + ApiEndpointUtils.escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            log.error("RocksDB store request failed: {}", e.getMessage(), e);
            endpoint.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleGet(HttpExchange exchange, List<String> segments) throws IOException {
        RocksDbMailboxStore store = RocksDbMailboxStoreManager.getConfiguredStore();
        String state = ApiEndpointUtils.parseQuery(exchange.getRequestURI()).get("state");
        if (segments.size() == 2) {
            endpoint.sendJson(exchange, 200, gson.toJson(store.getMailbox(segments.get(0), segments.get(1), state)));
            return;
        }
        if (segments.size() >= 3 && "folders".equals(segments.get(2))) {
            if (segments.size() >= 5 && "properties".equals(segments.getLast())) {
                String folder = joinSegments(segments, 3, segments.size() - 1);
                endpoint.sendJson(exchange, 200, gson.toJson(store.getFolderProperties(segments.get(0), segments.get(1), folder)));
                return;
            }
            if (segments.size() >= 4) {
                String folder = joinSegments(segments, 3, segments.size());
                endpoint.sendJson(exchange, 200, gson.toJson(store.getFolder(segments.get(0), segments.get(1), folder, state)));
                return;
            }
        }
        if (segments.size() == 4 && "messages".equals(segments.get(2))) {
            var message = store.getMessage(segments.get(0), segments.get(1), segments.get(3));
            if (message.isPresent()) {
                endpoint.sendJson(exchange, 200, gson.toJson(message.get()));
            } else {
                endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            }
            return;
        }
        endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleMutation(HttpExchange exchange, List<String> segments) throws IOException {
        if (segments.size() < 3) {
            endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        String method = exchange.getRequestMethod();
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        RocksDbMailboxStore store = RocksDbMailboxStoreManager.getConfiguredStore();
        String domain = segments.get(0);
        String user = segments.get(1);

        if ("folders".equals(segments.get(2))) {
            handleFolderMutation(exchange, method, segments, body, store, domain, user);
            return;
        }
        if ("messages".equals(segments.get(2))) {
            handleMessageMutation(exchange, method, segments, body, store, domain, user);
            return;
        }

        endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleFolderMutation(HttpExchange exchange, String method, List<String> segments, Map<String, Object> body,
                                      RocksDbMailboxStore store, String domain, String user) throws IOException {
        if ("POST".equalsIgnoreCase(method) && segments.size() == 3) {
            store.createFolder(domain, user,
                    String.valueOf(body.getOrDefault("parent", "")),
                    String.valueOf(body.getOrDefault("name", "")));
            endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder created.\"}");
            return;
        }
        if ("PATCH".equalsIgnoreCase(method) && segments.size() >= 4) {
            store.renameFolder(domain, user, joinSegments(segments, 3, segments.size()),
                    String.valueOf(body.getOrDefault("name", "")));
            endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder renamed.\"}");
            return;
        }
        if ("DELETE".equalsIgnoreCase(method) && segments.size() >= 4) {
            store.deleteFolder(domain, user, joinSegments(segments, 3, segments.size()));
            endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder removed.\"}");
            return;
        }
        if ("POST".equalsIgnoreCase(method) && segments.size() >= 5) {
            String action = segments.getLast();
            String folder = joinSegments(segments, 3, segments.size() - 1);
            if ("copy".equals(action)) {
                store.copyFolder(domain, user, folder,
                        String.valueOf(body.getOrDefault("destinationParent", "")),
                        String.valueOf(body.getOrDefault("newName", "")));
                endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder copied.\"}");
                return;
            }
            if ("move".equals(action)) {
                store.moveFolder(domain, user, folder, String.valueOf(body.getOrDefault("destinationParent", "")));
                endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder moved.\"}");
                return;
            }
        }
        endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleMessageMutation(HttpExchange exchange, String method, List<String> segments, Map<String, Object> body,
                                       RocksDbMailboxStore store, String domain, String user) throws IOException {
        if (!"POST".equalsIgnoreCase(method) || segments.size() != 4) {
            endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        String op = segments.get(3);
        switch (op) {
            case "move" -> {
                int moved = store.moveMessages(domain, user,
                        String.valueOf(body.getOrDefault("fromFolder", "")),
                        String.valueOf(body.getOrDefault("toFolder", "")),
                        ApiEndpointUtils.toStringList(body.get("messageIds")));
                endpoint.sendJson(exchange, 200, "{\"success\":" + (moved > 0) + ",\"moved\":" + moved + "}");
            }
            case "read-status" -> {
                int moved = store.updateReadStatus(domain, user,
                        String.valueOf(body.getOrDefault("folder", "")),
                        String.valueOf(body.getOrDefault("action", "")),
                        ApiEndpointUtils.toStringList(body.get("messageIds")));
                endpoint.sendJson(exchange, 200, "{\"moved\":" + moved + "}");
            }
            case "mark-all-read" -> {
                int moved = store.markAllRead(domain, user, String.valueOf(body.getOrDefault("folder", "")));
                endpoint.sendJson(exchange, 200, "{\"success\":true,\"moved\":" + moved + "}");
            }
            case "delete-all" -> {
                int deleted = store.deleteAllMessages(domain, user, String.valueOf(body.getOrDefault("folder", "")));
                endpoint.sendJson(exchange, 200, "{\"success\":true,\"deleted\":" + deleted + "}");
            }
            case "cleanup" -> {
                int affected = store.cleanupMessages(domain, user,
                        String.valueOf(body.getOrDefault("folder", "")),
                        ApiEndpointUtils.toInt(body.getOrDefault("months", 3), 3));
                endpoint.sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Cleanup complete.\",\"affected\":" + affected + "}");
            }
            default -> endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
        }
    }

    private String parsePath(String requestPath) {
        String relative = requestPath.length() > PATH.length() ? requestPath.substring(PATH.length()) : "/";
        String decoded = URLDecoder.decode(relative, StandardCharsets.UTF_8).replace('\\', '/');
        while (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        while (decoded.endsWith("/") && !decoded.isEmpty()) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    private String joinSegments(List<String> segments, int fromInclusive, int toExclusive) {
        return String.join("/", segments.subList(fromInclusive, toExclusive));
    }
}
