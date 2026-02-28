package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.main.Config;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Handler for the /store endpoint.
 *
 * <p>Provides storage browser and mutation operations for Maildir-style email storage:
 * <ul>
 *   <li><b>GET /store[/...]</b> — Browse directories and serve .eml files.</li>
 *   <li><b>POST/PATCH/DELETE /store/{domain}/{user}/folders/...</b> — Folder operations.</li>
 *   <li><b>POST /store/{domain}/{user}/messages/...</b> — Message operations.</li>
 *   <li><b>POST/PUT/DELETE /store/{domain}/{user}/drafts/...</b> — Draft operations.</li>
 * </ul>
 *
 * <p>Directory listings are returned as HTML with clickable links, or as JSON
 * if the Accept header contains "application/json".
 */
public class StoreHandler implements ApiHandler {
    private static final Logger log = LogManager.getLogger(StoreHandler.class);
    private static final String PATH = "/store";

    private final HttpEndpoint endpoint;
    private final HttpAuth auth;
    private final Gson gson = ApiEndpointUtils.getGson();
    private final String storagePathOverride;

    private final StoreFolderOperations folderOps;
    private final StoreMessageOperations messageOps;
    private final StoreDraftOperations draftOps;

    /**
     * Constructs a new StoreHandler.
     *
     * @param endpoint            The parent HTTP endpoint for response utilities.
     * @param auth                The authentication handler.
     * @param storagePathOverride Optional storage path override (null to use config).
     */
    public StoreHandler(HttpEndpoint endpoint, HttpAuth auth, String storagePathOverride) {
        this.endpoint = endpoint;
        this.auth = auth;
        this.storagePathOverride = (storagePathOverride == null || storagePathOverride.isBlank())
                ? null : storagePathOverride;
        this.folderOps = new StoreFolderOperations(this);
        this.messageOps = new StoreMessageOperations(this);
        this.draftOps = new StoreDraftOperations(this);
    }

    @Override
    public String getPath() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        log.debug("Handling store request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        String method = exchange.getRequestMethod();
        boolean jsonResponse = ApiEndpointUtils.acceptsJson(exchange);

        Path basePath = getBasePath();
        String decoded = parseStorePath(exchange.getRequestURI().getPath());

        if (decoded.contains("..")) {
            sendForbidden(exchange, jsonResponse);
            return;
        }

        Path target = decoded.isEmpty() ? basePath : basePath.resolve(decoded).toAbsolutePath().normalize();
        if (!target.startsWith(basePath)) {
            sendForbidden(exchange, jsonResponse);
            return;
        }

        List<String> segments = decoded.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.stream(decoded.split("/"))
                .filter(s -> s != null && !s.isBlank())
                .toList());

        // Route mutations to appropriate handlers.
        if (!"GET".equalsIgnoreCase(method)) {
            handleMutation(exchange, method, basePath, segments);
            return;
        }

        // Check for folder properties endpoint.
        if (isFolderPropertiesPath(segments)) {
            folderOps.handleFolderProperties(exchange, basePath, segments);
            return;
        }

        // Serve individual .eml files as text/plain.
        if (Files.isRegularFile(target)) {
            serveEmlFile(exchange, target, jsonResponse, decoded);
            return;
        }

        // Generate directory listing.
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            sendNotFound(exchange, jsonResponse);
            return;
        }

        if (jsonResponse) {
            Map<String, Object> response = new HashMap<>();
            response.put("path", "/" + decoded);
            response.put("items", buildStoreItems(target, decoded));
            endpoint.sendJson(exchange, 200, gson.toJson(response));
            return;
        }

        StorageDirectoryListing listing = new StorageDirectoryListing("/store");
        String template = endpoint.readResourceFile("store-browser-ui.html");
        String items = listing.generateItems(target, decoded);

        String html = template
                .replace("{{PATH}}", ApiEndpointUtils.escapeHtml("/" + decoded))
                .replace("{{ITEMS}}", items);

        endpoint.sendHtml(exchange, 200, html);
    }

    /**
     * Routes mutations to the appropriate handler based on URL structure.
     */
    private void handleMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        if (segments.size() < 3) {
            endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        String scope = segments.get(2);
        if ("folders".equals(scope)) {
            folderOps.handleMutation(exchange, method, basePath, segments);
            return;
        }
        if ("messages".equals(scope)) {
            messageOps.handleMutation(exchange, method, basePath, segments);
            return;
        }
        if ("drafts".equals(scope)) {
            draftOps.handleMutation(exchange, method, basePath, segments);
            return;
        }

        endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    // ========== Path Resolution Utilities ==========

    /**
     * Gets the base storage path from config or override.
     */
    Path getBasePath() {
        String base = storagePathOverride != null
                ? storagePathOverride
                : Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        return Paths.get(base).toAbsolutePath().normalize();
    }

    /**
     * Parses the store path from the request URI.
     */
    private String parseStorePath(String requestPath) {
        String prefix = "/store";
        String rel = requestPath.length() > prefix.length() ? requestPath.substring(prefix.length()) : "/";
        String decoded = URLDecoder.decode(rel, StandardCharsets.UTF_8).replace('\\', '/');

        while (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        while (decoded.endsWith("/") && !decoded.isEmpty()) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    /**
     * Resolves the user's root directory from path segments.
     */
    Path resolveUserRoot(Path basePath, List<String> segments) throws IOException {
        if (segments.size() < 2) {
            throw new IOException("Missing domain/user path");
        }
        Path userRoot = basePath.resolve(segments.get(0)).resolve(segments.get(1)).toAbsolutePath().normalize();
        if (!userRoot.startsWith(basePath)) {
            throw new IOException("Forbidden");
        }
        return userRoot;
    }

    /**
     * Resolves a folder path under the user's root directory.
     */
    Path resolveFolderPath(Path userRoot, String folderPath) throws IOException {
        String clean = folderPath == null ? "" : folderPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/") && !clean.isEmpty()) clean = clean.substring(0, clean.length() - 1);
        if (clean.contains("..")) throw new IOException("Forbidden");

        Path folder = clean.isEmpty() ? userRoot : userRoot.resolve(clean).toAbsolutePath().normalize();
        if (!folder.startsWith(userRoot)) throw new IOException("Forbidden");
        return folder;
    }

    /**
     * Normalizes a Maildir folder path.
     */
    String normalizeMaildirFolderPath(String folderPath) throws IOException {
        String clean = folderPath == null ? "" : folderPath.replace('\\', '/').trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/") && !clean.isEmpty()) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty() || "inbox".equalsIgnoreCase(clean)) {
            return "";
        }
        if (clean.contains("..")) {
            throw new IOException("Forbidden");
        }

        List<String> normalized = new ArrayList<>();
        for (String part : clean.split("/")) {
            String p = part == null ? "" : part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (normalized.isEmpty() && "inbox".equalsIgnoreCase(p)) {
                continue;
            }
            if (isMaildirInternalDirectoryName(p)) {
                normalized.add(p.toLowerCase());
                continue;
            }
            if (p.startsWith(".")) {
                normalized.add(p);
            } else {
                normalized.add("." + p);
            }
        }
        return String.join("/", normalized);
    }

    /**
     * Normalizes a Maildir folder name.
     */
    String normalizeMaildirFolderName(String rawName) throws IOException {
        String name = rawName == null ? "" : rawName.trim().replace('\\', '/');
        if (name.isBlank()) {
            throw new IOException("Folder name is required.");
        }
        if (name.contains("/") || ".".equals(name)) {
            throw new IOException("Invalid folder name.");
        }
        if (name.contains("..")) {
            throw new IOException("Forbidden");
        }
        if (name.startsWith(".")) {
            return name;
        }
        return "." + name;
    }

    /**
     * Resolves a folder path with Maildir normalization.
     */
    Path resolveStoreFolderPath(Path userRoot, String folderPath) throws IOException {
        return resolveFolderPath(userRoot, normalizeMaildirFolderPath(folderPath));
    }

    /**
     * Resolves an existing folder path, checking both normalized and legacy paths.
     */
    Path resolveExistingStoreFolderPath(Path userRoot, String folderPath) throws IOException {
        Path normalized = resolveStoreFolderPath(userRoot, folderPath);
        if (Files.exists(normalized)) {
            return normalized;
        }
        Path legacy = resolveFolderPath(userRoot, folderPath);
        if (Files.exists(legacy)) {
            return legacy;
        }
        return normalized;
    }

    /**
     * Resolves the appropriate Maildir leaf directory.
     */
    Path resolveMaildirLeaf(Path folderPath, String preferredLeaf) {
        if (isMaildirLeaf(folderPath)) {
            if (folderPath.getFileName() != null && preferredLeaf.equals(folderPath.getFileName().toString())) {
                return folderPath;
            }
            Path parent = folderPath.getParent();
            return parent == null ? folderPath : parent.resolve(preferredLeaf);
        }
        return folderPath.resolve(preferredLeaf);
    }

    /**
     * Checks if a path is a Maildir leaf directory.
     */
    boolean isMaildirLeaf(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return "new".equals(name) || "cur".equals(name);
    }

    /**
     * Checks if a name is a Maildir internal directory name.
     */
    boolean isMaildirInternalDirectoryName(String name) {
        if (name == null) return false;
        return "new".equals(name) || "cur".equals(name) || "tmp".equals(name);
    }

    /**
     * Joins segments of a path from a list.
     */
    String joinSegments(List<String> segments, int fromInclusive, int toExclusive) {
        if (fromInclusive >= toExclusive || fromInclusive >= segments.size()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive && i < segments.size(); i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(segments.get(i));
        }
        return sb.toString();
    }

    // ========== File Utilities ==========

    /**
     * Checks if a path is a regular .eml file.
     */
    boolean isEmlFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".eml");
    }

    /**
     * Recursively copies a directory and its contents.
     */
    void copyRecursively(Path source, Path target) throws IOException {
        if (!Files.exists(source)) throw new IOException("Source not found");
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : walk.toList()) {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel).toAbsolutePath().normalize();
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ========== Response Helpers ==========

    /**
     * Sends the HTTP endpoint reference for use by operation helpers.
     */
    HttpEndpoint getEndpoint() {
        return endpoint;
    }

    /**
     * Gets the Gson instance.
     */
    Gson getGson() {
        return gson;
    }

    private void sendForbidden(HttpExchange exchange, boolean jsonResponse) throws IOException {
        if (jsonResponse) {
            endpoint.sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
        } else {
            endpoint.sendText(exchange, 403, "Forbidden");
        }
    }

    private void sendNotFound(HttpExchange exchange, boolean jsonResponse) throws IOException {
        if (jsonResponse) {
            endpoint.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
        } else {
            endpoint.sendText(exchange, 404, "Not Found");
        }
    }

    /**
     * Checks if the path corresponds to folder properties endpoint.
     */
    private boolean isFolderPropertiesPath(List<String> segments) {
        return segments.size() >= 5
                && "folders".equals(segments.get(2))
                && "properties".equals(segments.get(segments.size() - 1));
    }

    /**
     * Serves an individual .eml file.
     */
    private void serveEmlFile(HttpExchange exchange, Path target, boolean jsonResponse, String decodedPath) throws IOException {
        if (!target.getFileName().toString().toLowerCase().endsWith(".eml")) {
            sendNotFound(exchange, jsonResponse);
            return;
        }

        if (jsonResponse) {
            Map<String, Object> response = new HashMap<>();
            response.put("path", "/store/" + decodedPath);
            response.put("name", target.getFileName().toString());
            response.put("size", Files.size(target));
            response.put("content", Files.readString(target, StandardCharsets.UTF_8));
            endpoint.sendJson(exchange, 200, gson.toJson(response));
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        long len = Files.size(target);
        exchange.sendResponseHeaders(200, len);

        try (OutputStream os = exchange.getResponseBody();
             InputStream is = Files.newInputStream(target)) {
            is.transferTo(os);
        }
        log.debug("Served eml file: {} ({} bytes)", target.toString(), len);
    }

    /**
     * Builds a list of store items for JSON response.
     */
    private List<Map<String, Object>> buildStoreItems(Path targetPath, String relativePath) throws IOException {
        List<Path> children;
        try (Stream<Path> stream = Files.list(targetPath)) {
            children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase())).toList();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        if (!relativePath.isEmpty()) {
            Path parent = Paths.get(relativePath).getParent();
            String parentPath = parent == null ? "" : parent.toString().replace('\\', '/');
            Map<String, Object> parentItem = new HashMap<>();
            parentItem.put("type", "dir");
            parentItem.put("name", "..");
            parentItem.put("path", "/store/" + parentPath);
            items.add(parentItem);
        }

        for (Path child : children) {
            String name = child.getFileName().toString();
            if (Files.isDirectory(child)) {
                Map<String, Object> item = new HashMap<>();
                item.put("type", "dir");
                item.put("name", name);
                item.put("path", "/store/" + (relativePath.isEmpty() ? name : relativePath + "/" + name) + "/");
                items.add(item);
            } else if (isEmlFile(child)) {
                Map<String, Object> item = new HashMap<>();
                item.put("type", "file");
                item.put("name", name);
                item.put("path", "/store/" + (relativePath.isEmpty() ? name : relativePath + "/" + name));
                item.put("size", Files.size(child));
                items.add(item);
            }
        }
        return items;
    }
}

