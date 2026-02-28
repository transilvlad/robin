package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Handles message-related store operations.
 *
 * <p>Operations include:
 * <ul>
 *   <li>Move messages (POST /store/{domain}/{user}/messages/move)</li>
 *   <li>Update read status (POST /store/{domain}/{user}/messages/read-status)</li>
 *   <li>Mark all as read (POST /store/{domain}/{user}/messages/mark-all-read)</li>
 *   <li>Delete all messages (POST /store/{domain}/{user}/messages/delete-all)</li>
 *   <li>Cleanup old messages (POST /store/{domain}/{user}/messages/cleanup)</li>
 * </ul>
 */
class StoreMessageOperations {
    private static final Logger log = LogManager.getLogger(StoreMessageOperations.class);

    private final StoreHandler handler;

    StoreMessageOperations(StoreHandler handler) {
        this.handler = handler;
    }

    /**
     * Handles message mutation requests.
     */
    void handleMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        if (!"POST".equalsIgnoreCase(method) || segments.size() != 4) {
            handler.getEndpoint().sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        try {
            Path userRoot = handler.resolveUserRoot(basePath, segments);
            String op = segments.get(3);
            Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());

            switch (op) {
                case "move" -> handleMove(exchange, userRoot, body);
                case "read-status" -> handleReadStatus(exchange, userRoot, body);
                case "mark-all-read" -> handleMarkAllRead(exchange, userRoot, body);
                case "delete-all" -> handleDeleteAll(exchange, userRoot, body);
                case "cleanup" -> handleCleanup(exchange, userRoot, body);
                default -> handler.getEndpoint().sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                handler.getEndpoint().sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            handler.getEndpoint().sendJson(exchange, 400, "{\"error\":\"" + ApiEndpointUtils.escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            log.error("Store message mutation failed: {}", e.getMessage());
            handler.getEndpoint().sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleMove(HttpExchange exchange, Path userRoot, Map<String, Object> body) throws IOException {
        String fromFolder = String.valueOf(body.getOrDefault("fromFolder", "")).trim();
        String toFolder = String.valueOf(body.getOrDefault("toFolder", "")).trim();
        List<String> ids = ApiEndpointUtils.toStringList(body.get("messageIds"));
        int moved = moveMessages(userRoot, fromFolder, toFolder, ids);
        Map<String, Object> response = new HashMap<>();
        response.put("success", moved > 0);
        response.put("moved", moved);
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleReadStatus(HttpExchange exchange, Path userRoot, Map<String, Object> body) throws IOException {
        String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
        String action = String.valueOf(body.getOrDefault("action", "")).trim();
        List<String> ids = ApiEndpointUtils.toStringList(body.get("messageIds"));
        int moved = updateReadStatus(userRoot, folder, action, ids);
        handler.getEndpoint().sendJson(exchange, 200, "{\"moved\":" + moved + "}");
    }

    private void handleMarkAllRead(HttpExchange exchange, Path userRoot, Map<String, Object> body) throws IOException {
        String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
        int moved = markAllRead(userRoot, folder);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("moved", moved);
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleDeleteAll(HttpExchange exchange, Path userRoot, Map<String, Object> body) throws IOException {
        String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
        int deleted = deleteAllMessages(userRoot, folder);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("deleted", deleted);
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleCleanup(HttpExchange exchange, Path userRoot, Map<String, Object> body) throws IOException {
        String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
        int months = ApiEndpointUtils.toInt(body.getOrDefault("months", 3), 3);
        int affected = cleanupMessages(userRoot, folder, months);
        Map<String, Object> response = new HashMap<>();
        response.put("r", 1);
        response.put("msg", "Cleanup complete.");
        response.put("affected", affected);
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    /**
     * Moves messages between folders.
     */
    private int moveMessages(Path userRoot, String fromFolder, String toFolder, List<String> messageIds) throws IOException {
        Path from = handler.resolveStoreFolderPath(userRoot, fromFolder);
        Path toLeaf = handler.resolveMaildirLeaf(handler.resolveStoreFolderPath(userRoot, toFolder), "new");
        Files.createDirectories(toLeaf);
        int moved = 0;
        for (String id : messageIds) {
            String normalizedId = normalizeMessageId(id);
            if (normalizedId == null) continue;
            Path src = findMessagePath(from, normalizedId);
            if (src == null) {
                src = findMessagePathAnywhere(userRoot, normalizedId);
            }
            if (src == null) continue;
            Path target = toLeaf.resolve(normalizedId).toAbsolutePath().normalize();
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
            moved++;
        }
        return moved;
    }

    /**
     * Updates read/unread status of messages.
     */
    private int updateReadStatus(Path userRoot, String folder, String action, List<String> messageIds) throws IOException {
        Path folderPath = handler.resolveStoreFolderPath(userRoot, folder);
        Path srcDir;
        Path dstDir;
        if ("read".equalsIgnoreCase(action)) {
            srcDir = handler.resolveMaildirLeaf(folderPath, "new");
            dstDir = handler.resolveMaildirLeaf(folderPath, "cur");
        } else if ("unread".equalsIgnoreCase(action)) {
            srcDir = handler.resolveMaildirLeaf(folderPath, "cur");
            dstDir = handler.resolveMaildirLeaf(folderPath, "new");
        } else {
            throw new IOException("Invalid action");
        }
        Files.createDirectories(dstDir);
        int moved = 0;
        for (String id : messageIds) {
            String normalizedId = normalizeMessageId(id);
            if (normalizedId == null) continue;
            Path src = srcDir.resolve(normalizedId);
            if (!Files.exists(src)) continue;
            Files.move(src, dstDir.resolve(normalizedId), StandardCopyOption.REPLACE_EXISTING);
            moved++;
        }
        return moved;
    }

    /**
     * Marks all messages in a folder as read.
     */
    private int markAllRead(Path userRoot, String folder) throws IOException {
        Path root = handler.resolveStoreFolderPath(userRoot, folder);
        Path src = handler.resolveMaildirLeaf(root, "new");
        Path dst = handler.resolveMaildirLeaf(root, "cur");
        Files.createDirectories(dst);
        if (!Files.isDirectory(src)) return 0;
        int moved = 0;
        try (Stream<Path> list = Files.list(src)) {
            for (Path file : list.filter(Files::isRegularFile).toList()) {
                Files.move(file, dst.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
        }
        return moved;
    }

    /**
     * Deletes all messages in a folder.
     */
    private int deleteAllMessages(Path userRoot, String folder) throws IOException {
        Path root = handler.resolveStoreFolderPath(userRoot, folder);
        if (handler.isMaildirLeaf(root)) {
            return deleteMessagesInDir(root);
        }
        int deleted = 0;
        deleted += deleteMessagesInDir(root);
        deleted += deleteMessagesInDir(root.resolve("new"));
        deleted += deleteMessagesInDir(root.resolve("cur"));
        return deleted;
    }

    /**
     * Cleans up messages older than specified months.
     */
    private int cleanupMessages(Path userRoot, String folder, int months) throws IOException {
        Path root = handler.resolveStoreFolderPath(userRoot, folder);
        return cleanupMessagesInTree(root, months);
    }

    private int cleanupMessagesInTree(Path root, int months) throws IOException {
        Instant cutoff = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(Math.max(0, months))
                .toInstant();
        int affected = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().toLowerCase().endsWith(".eml")) continue;
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    affected++;
                }
            }
        }
        return affected;
    }

    private int deleteMessagesInDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int deleted = 0;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().toLowerCase().endsWith(".eml")) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * Normalizes a message ID by extracting the filename portion.
     */
    private String normalizeMessageId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.replace('\\', '/');
        if (id.contains("..")) {
            return null;
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    /**
     * Finds a message file within a folder.
     */
    private Path findMessagePath(Path folderRoot, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        Path direct = folderRoot.resolve(messageId);
        if (Files.exists(direct)) return direct;

        if (handler.isMaildirLeaf(folderRoot)) {
            Path parent = folderRoot.getParent();
            if (parent == null) return null;
            String leaf = folderRoot.getFileName() == null ? "" : folderRoot.getFileName().toString();
            String sibling = "new".equals(leaf) ? "cur" : "new";
            Path inSibling = parent.resolve(sibling).resolve(messageId);
            if (Files.exists(inSibling)) return inSibling;
            return null;
        }

        Path inNew = folderRoot.resolve("new").resolve(messageId);
        if (Files.exists(inNew)) return inNew;
        Path inCur = folderRoot.resolve("cur").resolve(messageId);
        if (Files.exists(inCur)) return inCur;
        return null;
    }

    /**
     * Searches for a message file anywhere under the user's root.
     */
    private Path findMessagePathAnywhere(Path userRoot, String messageId) throws IOException {
        Path quick = findMessagePath(userRoot, messageId);
        if (quick != null) {
            return quick;
        }

        try (Stream<Path> walk = Files.walk(userRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (messageId.equals(file.getFileName().toString())) {
                    return file;
                }
            }
        }
        return null;
    }
}

