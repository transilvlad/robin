package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles draft-related store operations.
 *
 * <p>Operations include:
 * <ul>
 *   <li>Create draft (POST /store/{domain}/{user}/drafts)</li>
 *   <li>Update draft (PUT /store/{domain}/{user}/drafts/{draftId})</li>
 *   <li>Delete draft (DELETE /store/{domain}/{user}/drafts/{draftId})</li>
 *   <li>Add attachment (POST /store/{domain}/{user}/drafts/{draftId}/attachments)</li>
 *   <li>Delete attachment (DELETE /store/{domain}/{user}/drafts/{draftId}/attachments/{attachmentId})</li>
 * </ul>
 */
class StoreDraftOperations {
    private static final Logger log = LogManager.getLogger(StoreDraftOperations.class);

    private final StoreHandler handler;

    StoreDraftOperations(StoreHandler handler) {
        this.handler = handler;
    }

    /**
     * Handles draft mutation requests.
     */
    void handleMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = handler.resolveUserRoot(basePath, segments);
            Path draftsNew = userRoot.resolve(".Drafts").resolve("new").toAbsolutePath().normalize();
            if (!draftsNew.startsWith(userRoot)) throw new IOException("Forbidden");
            Files.createDirectories(draftsNew);

            // POST /store/{domain}/{user}/drafts — Create draft.
            if ("POST".equalsIgnoreCase(method) && segments.size() == 3) {
                handleCreate(exchange, draftsNew);
                return;
            }

            // PUT/DELETE /store/{domain}/{user}/drafts/{draftId}
            if (segments.size() == 4) {
                String draftId = segments.get(3);
                Path draftFile = draftsNew.resolve(draftId).toAbsolutePath().normalize();
                if (!draftFile.startsWith(draftsNew)) throw new IOException("Forbidden");

                if ("PUT".equalsIgnoreCase(method)) {
                    handleUpdate(exchange, draftFile, draftId);
                    return;
                }

                if ("DELETE".equalsIgnoreCase(method)) {
                    handleDelete(exchange, userRoot, draftFile, draftId);
                    return;
                }
            }

            // POST /store/{domain}/{user}/drafts/{draftId}/attachments
            if ("POST".equalsIgnoreCase(method) && segments.size() == 5 && "attachments".equals(segments.get(4))) {
                String draftId = segments.get(3);
                handleAddAttachment(exchange, userRoot, draftId);
                return;
            }

            // DELETE /store/{domain}/{user}/drafts/{draftId}/attachments/{attachmentId}
            if ("DELETE".equalsIgnoreCase(method) && segments.size() == 6 && "attachments".equals(segments.get(4))) {
                String draftId = segments.get(3);
                String attachmentId = segments.get(5);
                handleDeleteAttachment(exchange, userRoot, draftId, attachmentId);
                return;
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                handler.getEndpoint().sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            handler.getEndpoint().sendJson(exchange, 400, "{\"error\":\"" + ApiEndpointUtils.escapeJson(e.getMessage()) + "\"}");
            return;
        } catch (Exception e) {
            log.error("Store draft mutation failed: {}", e.getMessage());
            handler.getEndpoint().sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            return;
        }

        handler.getEndpoint().sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleCreate(HttpExchange exchange, Path draftsNew) throws IOException {
        String draftId = "draft-" + UUID.randomUUID() + ".eml";
        Path draftFile = draftsNew.resolve(draftId);
        byte[] draftBytes = readDraftBytes(exchange);
        Files.write(draftFile, draftBytes);

        Map<String, Object> response = new HashMap<>();
        response.put("r", 1);
        response.put("draftId", draftId);
        response.put("msg", "mail successfully saved.");
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleUpdate(HttpExchange exchange, Path draftFile, String draftId) throws IOException {
        byte[] draftBytes = readDraftBytes(exchange);
        Files.write(draftFile, draftBytes);
        Map<String, Object> response = new HashMap<>();
        response.put("r", 1);
        response.put("draftId", draftId);
        response.put("msg", "mail successfully saved.");
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleDelete(HttpExchange exchange, Path userRoot, Path draftFile, String draftId) throws IOException {
        Files.deleteIfExists(draftFile);
        Files.deleteIfExists(userRoot.resolve(".Drafts").resolve("cur").resolve(draftId));
        Files.deleteIfExists(userRoot.resolve(".Drafts").resolve(draftId));
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1,\"msg\":\"mail successfully discarded.\"}");
    }

    private void handleAddAttachment(HttpExchange exchange, Path userRoot, String draftId) throws IOException {
        Path attachmentsDir = userRoot.resolve(".Drafts").resolve("attachments").resolve(draftId).toAbsolutePath().normalize();
        if (!attachmentsDir.startsWith(userRoot)) throw new IOException("Forbidden");
        Files.createDirectories(attachmentsDir);

        byte[] attachment = ApiEndpointUtils.readUploadedEmlBytes(exchange);
        String name = ApiEndpointUtils.normalizeUploadFileName(
                ApiEndpointUtils.parseQuery(exchange.getRequestURI()).get("filename"));
        String attachmentId = "att-" + UUID.randomUUID() + "-" + name;
        Path file = attachmentsDir.resolve(attachmentId);
        Files.write(file, attachment);

        Map<String, Object> response = new HashMap<>();
        response.put("r", 1);
        response.put("f", List.of(attachmentId));
        handler.getEndpoint().sendJson(exchange, 200, handler.getGson().toJson(response));
    }

    private void handleDeleteAttachment(HttpExchange exchange, Path userRoot, String draftId, String attachmentId) throws IOException {
        Path attachmentsDir = userRoot.resolve(".Drafts").resolve("attachments").resolve(draftId).toAbsolutePath().normalize();
        Path file = attachmentsDir.resolve(attachmentId).toAbsolutePath().normalize();
        if (!file.startsWith(attachmentsDir) || !attachmentsDir.startsWith(userRoot)) throw new IOException("Forbidden");
        Files.deleteIfExists(file);
        handler.getEndpoint().sendJson(exchange, 200, "{\"r\":1}");
    }

    /**
     * Reads draft content from the request, either as raw upload or JSON body.
     */
    private byte[] readDraftBytes(HttpExchange exchange) throws IOException {
        if (ApiEndpointUtils.isRawUploadRequest(exchange)) {
            return ApiEndpointUtils.readUploadedEmlBytes(exchange);
        }
        Map<String, Object> body = ApiEndpointUtils.parseJsonBody(exchange.getRequestBody());
        String from = String.valueOf(body.getOrDefault("from", ""));
        String to = String.valueOf(body.getOrDefault("to", ""));
        String subject = String.valueOf(body.getOrDefault("subject", ""));
        String message = String.valueOf(body.getOrDefault("message", body.getOrDefault("body", "")));
        StringBuilder eml = new StringBuilder();
        if (!from.isBlank()) eml.append("From: ").append(from).append("\r\n");
        if (!to.isBlank()) eml.append("To: ").append(to).append("\r\n");
        if (!subject.isBlank()) eml.append("Subject: ").append(subject).append("\r\n");
        eml.append("\r\n").append(message == null ? "" : message);
        return eml.toString().getBytes(StandardCharsets.UTF_8);
    }
}

