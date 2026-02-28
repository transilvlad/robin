package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Handles folder-related store operations.
 *
 * <p>Operations include:
 * <ul>
 *   <li>Create folder (POST /store/{domain}/{user}/folders)</li>
 *   <li>Rename folder (PATCH /store/{domain}/{user}/folders/{folder})</li>
 *   <li>Delete folder (DELETE /store/{domain}/{user}/folders/{folder})</li>
 *   <li>Copy folder (POST /store/{domain}/{user}/folders/{folder}/copy)</li>
 *   <li>Move folder (POST /store/{domain}/{user}/folders/{folder}/move)</li>
 *   <li>Get folder properties (GET /store/{domain}/{user}/folders/{folder}/properties)</li>
 * </ul>
 */
class StoreFolderOperations {
    private static final Logger log = LogManager.getLogger(StoreFolderOperations.class);

    private final StoreHandler handler;

    StoreFolderOperations(StoreHandler handler) {
        this.handler = handler;
    }

    /**
     * Handles folder mutation requests (POST, PATCH, DELETE).
     */
    void handleMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = handler.resolveUserRoot(basePath, segments);

            // POST /store/{domain}/{user}/folders — Create folder.
            if ("POST".equalsIgnoreCase(method) && segments.size() == 3) {
                handleCreate(exchange, userRoot);
                return;
            }

            // POST /store/{domain}/{user}/folders/{folder}/copy|move
            if ("POST".equalsIgnoreCase(method) && segments.size() >= 5) {
                String action = segments.get(segments.size() - 1);
                String folderRel = handler.joinSegments(segments, 3, segments.size() - 1);
                if ("copy".equals(action)) {
                    handleCopy(exchange, userRoot, folderRel);
                    return;
                }
                if ("move".equals(action)) {
                    handleMove(exchange, userRoot, folderRel);
                    return;
                }
            }

            // PATCH /store/{domain}/{user}/folders/{folder} — Rename folder.
            if ("PATCH".equalsIgnoreCase(method) && segments.size() >= 4) {
                String folderRel = handler.joinSegments(segments, 3, segments.size());
                handleRename(exchange, userRoot, folderRel);
                return;
            }

            // DELETE /store/{domain}/{user}/folders/{folder} — Delete folder.
            if ("DELETE".equalsIgnoreCase(method) && segments.size() >= 4) {
                String folderRel = handler.joinSegments(segments, 3, segments.size());
                handleDelete(exchange, userRoot, folderRel);
                return;
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                handler.getEndpoint().sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"" + ApiEndpointUtils.escapeJson(e.getMessage()) + "\"}");
            return;
        } catch (Exception e) {
            log.error("Store folder mutation failed: {}", e.getMessage());
            handler.getEndpoint().sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            return;
        }

        handler.getEndpoint().sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    /**
     * Handles folder properties request (GET).
     */
    void handleFolderProperties(HttpExchange exchange, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = handler.resolveUserRoot(basePath, segments);
            String folderRel = handler.joinSegments(segments, 3, segments.size() - 1);
            Path folder = handler.resolveExistingStoreFolderPath(userRoot, folderRel);
            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                handler.getEndpoint().sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
                return;
            }

            Path unreadLeaf = handler.resolveMaildirLeaf(folder, "new");
            Path readLeaf = handler.resolveMaildirLeaf(folder, "cur");

            int unread = countEmlFilesInDirectory(unreadLeaf);
            int read = countEmlFilesInDirectory(readLeaf);
            int total = unread + read;
            long size = sumEmlSizeInDirectory(unreadLeaf) + sumEmlSizeInDirectory(readLeaf);

            Map<String, Object> response = new HashMap<>();
            response.put("r", 1);
            response.put("size", size);
            response.put("unread", unread);
            response.put("total", total);
            handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                handler.getEndpoint().sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"" + ApiEndpointUtils.escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            log.error("Store folder properties failed: {}", e.getMessage());
            handler.getEndpoint().sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleCreate(HttpExchange exchange, Path userRoot) throws IOException {
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        String parent = handler.normalizeMaildirFolderPath(String.valueOf(body.getOrDefault("parent", "")));
        String name = handler.normalizeMaildirFolderName(String.valueOf(body.getOrDefault("name", "")));
        Path folder = handler.resolveFolderPath(userRoot, parent.isBlank() ? name : (parent + "/" + name));
        Files.createDirectories(folder.resolve("new"));
        Files.createDirectories(folder.resolve("cur"));
        Files.createDirectories(folder.resolve("tmp"));
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder created.\"}");
    }

    private void handleRename(HttpExchange exchange, Path userRoot, String folderRel) throws IOException {
        Path source = handler.resolveExistingStoreFolderPath(userRoot, folderRel);
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            handler.getEndpoint().sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
            return;
        }
        if (source.equals(userRoot)) {
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Cannot modify inbox root.\"}");
            return;
        }
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        String newName = handler.normalizeMaildirFolderName(String.valueOf(body.getOrDefault("name", "")));
        Path target = source.getParent().resolve(newName).toAbsolutePath().normalize();
        if (!target.startsWith(userRoot)) throw new IOException("Forbidden");
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder renamed.\"}");
    }

    private void handleDelete(HttpExchange exchange, Path userRoot, String folderRel) throws IOException {
        Path source = handler.resolveExistingStoreFolderPath(userRoot, folderRel);
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            handler.getEndpoint().sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
            return;
        }
        if (source.equals(userRoot)) {
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Cannot modify inbox root.\"}");
            return;
        }
        boolean hasFiles;
        try (Stream<Path> walk = Files.walk(source)) {
            hasFiles = walk.anyMatch(Files::isRegularFile);
        }
        if (hasFiles) {
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Folder is not empty.\"}");
            return;
        }
        handler.deleteRecursively(source);
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder removed.\"}");
    }

    private void handleCopy(HttpExchange exchange, Path userRoot, String folderRel) throws IOException {
        Path source = handler.resolveExistingStoreFolderPath(userRoot, folderRel);
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            handler.getEndpoint().sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
            return;
        }
        if (source.equals(userRoot)) {
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Cannot modify inbox root.\"}");
            return;
        }
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        String destinationParent = handler.normalizeMaildirFolderPath(String.valueOf(body.getOrDefault("destinationParent", "")));
        String defaultName = source.getFileName() == null ? "" : source.getFileName().toString();
        String newName = handler.normalizeMaildirFolderName(String.valueOf(body.getOrDefault("newName", defaultName)));
        Path targetParent = handler.resolveFolderPath(userRoot, destinationParent);
        Files.createDirectories(targetParent);
        Path target = targetParent.resolve(newName).toAbsolutePath().normalize();
        if (!target.startsWith(userRoot)) throw new IOException("Forbidden");
        handler.copyRecursively(source, target);
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder copied.\"}");
    }

    private void handleMove(HttpExchange exchange, Path userRoot, String folderRel) throws IOException {
        Path source = handler.resolveExistingStoreFolderPath(userRoot, folderRel);
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            handler.getEndpoint().sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
            return;
        }
        if (source.equals(userRoot)) {
            handler.getEndpoint().sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Cannot modify inbox root.\"}");
            return;
        }
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        String destinationParent = handler.normalizeMaildirFolderPath(String.valueOf(body.getOrDefault("destinationParent", "")));
        Path targetParent = handler.resolveFolderPath(userRoot, destinationParent);
        Files.createDirectories(targetParent);
        Path target = targetParent.resolve(source.getFileName()).toAbsolutePath().normalize();
        if (!target.startsWith(userRoot)) throw new IOException("Forbidden");
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder moved.\"}");
    }

    private int countEmlFilesInDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return (int) list.filter(handler::isEmlFile).count();
        }
    }

    private long sumEmlSizeInDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        long size = 0L;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(handler::isEmlFile).toList()) {
                size += Files.size(file);
            }
        }
        return size;
    }
}

