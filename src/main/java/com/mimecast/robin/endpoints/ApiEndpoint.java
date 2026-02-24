package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.config.server.UserConfig;
import com.mimecast.robin.db.SharedDataSource;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.GsonExclusionStrategy;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.PathUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API endpoint for case submission and queue management.
 *
 * <p>Starts a lightweight HTTP server to accept JSON/JSON5 case definitions
 * (either as raw JSON in the body or by providing a file path) and executes the client.
 *
 * <p>Endpoint:
 * <ul>
 *   <li><b>GET /</b> — Serves a simple HTML landing page documenting available API endpoints.</li>
 *   <li><b>POST /client/send</b> — Accepts either a JSON/JSON5 payload describing a case or a query parameter
 *       <code>path</code> that points to a case file on disk. It executes the SMTP client with the supplied case and
 *       responds with the final {@link Session} serialized as JSON.</li>
 *   <li><b>POST /client/queue</b> — Same inputs as <code>/client/send</code>, but instead of sending immediately, it
 *       enqueues the built {@link Session} as a {@link RelaySession} into the persistent relay queue.</li>
 *   <li><b>GET /client/queue/list</b> — Lists the current relay queue contents in a simple HTML table.</li>
 *   <li><b>GET /logs</b> — Searches log files for lines matching a query string. Supports text/plain GET requests only.
 *       Returns usage message if no query parameter is set. Searches current and previous log4j2 log files.</li>
 *   <li><b>GET /users</b> — Returns all configured users from either SQL backend or users config list.</li>
 *   <li><b>GET /users/{username}/exists</b> — Checks if a user exists.</li>
 *   <li><b>POST /users/authenticate</b> — Validates user credentials from JSON body.</li>
 *   <li><b>GET /store[/...]</b> — Browse local message storage. Directory listings are returned as HTML with clickable
 *       links; individual <code>.eml</code> files are returned as <code>text/plain</code>. If
 *       <code>Accept: application/json</code> is sent, listings and files are returned as JSON for integrations.
 *       Empty folders are ignored.</li>
 *   <li><b>GET /health</b> — Simple liveness endpoint returning HTTP 200 with <code>{"status":"UP"}</code>.</li>
 * </ul>
 *
 * <p>Response serialization excludes heavy or sensitive fields to keep payloads compact and safe:
 * <ul>
 *   <li>{@link Session#putMagic(String, Object) magic} map</li>
 *   <li>{@link Session#getSavedResults() savedResults} map</li>
 *   <li>{@link MessageEnvelope#getStream() stream} and internal <code>bytes</code> backing</li>
 * </ul>
 */
public class ApiEndpoint extends HttpEndpoint {
    private static final Logger log = LogManager.getLogger(ApiEndpoint.class);

    /**
     * Gson instance used for serializing responses with an exclusion strategy
     * tailored to remove large or sensitive fields.
     */
    private Gson gson;


    /**
     * Queue operations handler for managing queue-related requests.
     */
    private QueueOperationsHandler queueHandler;
    private String storagePathOverride;

    /**
     * Starts the API endpoint with endpoint configuration.
     *
     * @param config EndpointConfig containing port and authentication settings (authType, authValue, allowList).
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start(EndpointConfig config) throws IOException {
        // Initialize authentication handler.
        this.auth = new HttpAuth(config, "API");
        String configuredStoragePath = config.getStringProperty("storagePath");
        this.storagePathOverride = (configuredStoragePath == null || configuredStoragePath.isBlank())
                ? null
                : configuredStoragePath;

        // Initialize queue operations handler.
        this.queueHandler = new QueueOperationsHandler(this);

        // Build a Gson serializer that excludes fields we don't want to expose.
        gson = new GsonBuilder()
                .addSerializationExclusionStrategy(new GsonExclusionStrategy())
                .setPrettyPrinting()
                .create();

        // Bind the HTTP server to the configured API port.
        int apiPort = config.getPort(8090);
        HttpServer server = HttpServer.create(new InetSocketAddress(apiPort), 10);

        // Register endpoints.

        // Landing page for API endpoint discovery.
        server.createContext("/", this::handleLandingPage);

        // Favicon.
        server.createContext("/favicon.ico", this::handleFavicon);

        // Main endpoint that triggers a Client.send(...) run for the supplied case.
        server.createContext("/client/send", this::handleClientSend);

        // Queue endpoint that enqueues a RelaySession for later delivery.
        server.createContext("/client/queue", this::handleClientQueue);

        // Queue listing endpoint.
        server.createContext("/client/queue/list", this::handleQueueList);

        // Queue control endpoints (delegated to QueueOperationsHandler).
        server.createContext("/client/queue/delete", queueHandler::handleDelete);
        server.createContext("/client/queue/retry", queueHandler::handleRetry);
        server.createContext("/client/queue/bounce", queueHandler::handleBounce);

        // Logs search endpoint.
        server.createContext("/logs", this::handleLogs);

        // User integration endpoints.
        server.createContext("/users", this::handleUsers);

        // Storage browser endpoint (directory listing and .eml serving).
        server.createContext("/store", this::handleStore);

        // Liveness endpoint for API.
        server.createContext("/health", exchange -> sendJson(exchange, 200, "{\"status\":\"UP\"}"));

        // Start the embedded server on a background thread.
        new Thread(server::start).start();
        log.info("Landing available at http://localhost:{}/", apiPort);
        log.info("Send endpoint available at http://localhost:{}/client/send", apiPort);
        log.info("Queue endpoint available at http://localhost:{}/client/queue", apiPort);
        log.info("Queue list available at http://localhost:{}/client/queue/list", apiPort);
        log.info("Queue delete available at http://localhost:{}/client/queue/delete", apiPort);
        log.info("Queue retry available at http://localhost:{}/client/queue/retry", apiPort);
        log.info("Queue bounce available at http://localhost:{}/client/queue/bounce", apiPort);
        log.info("Logs available at http://localhost:{}/logs", apiPort);
        log.info("Users available at http://localhost:{}/users", apiPort);
        log.info("Store available at http://localhost:{}/store/", apiPort);
        log.info("Health available at http://localhost:{}/health", apiPort);
        if (auth.isAuthEnabled()) {
            log.info("Authentication is enabled");
        }
    }

    /**
     * Serves a simple HTML landing page that documents available API endpoints.
     */
    private void handleLandingPage(HttpExchange exchange) throws IOException {
        log.debug("Handling landing page request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            String response = readResourceFile("api-endpoints-ui.html");
            sendHtml(exchange, 200, response);
            log.debug("Landing page served successfully");
        } catch (IOException e) {
            log.error("Could not read api-endpoints-ui.html", e);
            sendText(exchange, 500, "Internal Server Error");
        }
    }


    /**
     * Handles <b>POST /client/send</b> requests.
     * <p>Supports raw JSON/JSON5 describing the case — parsed and executed in-memory.
     * <p>On success, returns the final {@link Session} as filtered JSON.
     * On failure, returns HTTP 4xx/5xx with an explanatory message.
     */
    private void handleClientSend(HttpExchange exchange) throws IOException {
        // Only POST is supported, reject anything else.
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-POST request to /client/send: method={}", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        log.info("POST /client/send from {}", exchange.getRemoteAddress());
        try {
            if (isRawUploadRequest(exchange)) {
                handleClientSendRawUpload(exchange);
                return;
            }

            // Body mode: accept a raw JSON/JSON5 payload describing the case.
            String body = readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                log.error("/client/send empty request body");
                sendText(exchange, 400, "Empty request body");
                return;
            }
            log.debug("/client/send body length: {} bytes", body.getBytes(StandardCharsets.UTF_8).length);

            // Apply magic replacements similar to how file-based configs are processed.
            String processed = Magic.streamMagicReplace(body);

            // Parse into a Map and build CaseConfig.
            @SuppressWarnings("rawtypes")
            Map map = new Gson().fromJson(processed, Map.class);
            if (map == null || map.isEmpty()) {
                log.error("/client/send invalid JSON body");
                sendText(exchange, 400, "Invalid JSON body");
                return;
            }

            CaseConfig caseConfig = buildCaseConfig(map);

            // Create a new client instance and execute the case in-memory.
            Client client = new Client()
                    .setSkip(true) // Skip assertions for API runs.

                    .send(caseConfig);

            Session session = client.getSession();
            log.info("/client/send completed from body: sessionUID={}, envelopes={}",
                    session.getUID(), session.getEnvelopes() != null ? session.getEnvelopes().size() : 0);

            // Serialize session to JSON using the filtered Gson (excludes magic/savedResults/stream/bytes).
            String response = gson.toJson(session);
            sendJson(exchange, 200, response);
            log.debug("/client/send responded 200, bytes={}", response.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            // Any unexpected exception is reported as a 500 with a brief message.
            log.error("Error processing /client/send: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue</b> requests.
     * <p>Supports raw JSON/JSON5 describing the case — parsed and mapped in-memory.
     * <p>On success, enqueues a {@link RelaySession} for later delivery and returns a JSON confirmation with
     * the filtered {@link Session} plus queue size.
     */
    @SuppressWarnings("unchecked")
    private void handleClientQueue(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-POST request to /client/queue: method={}", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        log.info("POST /client/queue from {}", exchange.getRemoteAddress());
        try {
            if (isRawUploadRequest(exchange)) {
                handleClientQueueRawUpload(exchange);
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI());

            // Optional overrides via query params.
            String protocolOverride = query.getOrDefault("protocol", Config.getServer().getRelay().getStringProperty("protocol", "ESMTP"));
            // mailbox parameter is an override for dovecot folder delivery
            // Default to inboxFolder as most queued items are inbound
            String mailboxOverride = query.getOrDefault("mailbox",
                    Config.getServer().getDovecot().getSaveLda().getInboxFolder());
            log.debug("/client/queue overrides: protocol={}, mailbox={}", protocolOverride, mailboxOverride);

            String body = readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                log.info("/client/queue empty request body");
                sendText(exchange, 400, "Empty request body");
                return;
            }
            log.debug("/client/queue body length: {} bytes", body.getBytes(StandardCharsets.UTF_8).length);
            String processed = Magic.streamMagicReplace(body);

            Map<String, Object> map = new Gson().fromJson(processed, Map.class);
            if (map == null || map.isEmpty()) {
                log.info("/client/queue invalid JSON body");
                sendText(exchange, 400, "Invalid JSON body");
                return;
            }

            CaseConfig caseConfig = buildCaseConfig(map);

            // Map a session from the case without sending.
            Session session = Factories.getSession();
            session.map(caseConfig);
            log.info("Queueing session: sessionUID={}, envelopes={}",
                    session.getUID(), session.getEnvelopes() != null ? session.getEnvelopes().size() : 0);

            // Wrap in RelaySession using config or overrides.
            RelaySession relaySession = new RelaySession(session)
                    .setProtocol(protocolOverride)
                    .setMailbox(mailboxOverride);

            // Persist any envelope files to storage/queue before enqueueing.
            QueueFiles.persistEnvelopeFiles(relaySession);

            // Enqueue for later relay.
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
            queue.enqueue(relaySession);
            long size = queue.size();
            log.info("Relay session queued: protocol={}, mailbox={}, queueSize={}", protocolOverride, mailboxOverride, size);

            // Build confirmation payload.
            Map<String, Object> response = new HashMap<>();
            response.put("status", "QUEUED");
            response.put("queueSize", size);
            response.put("session", session);

            String json = gson.toJson(response);
            sendJson(exchange, 202, json);
            log.debug("/client/queue responded 202, bytes={}", json.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            log.error("Error processing /client/queue: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Lists the relay queue contents in a simple HTML table.
     * Supports pagination via query parameters: page (1-based) and limit (default 50).
     */
    private void handleQueueList(HttpExchange exchange) throws IOException {
        log.debug("GET /client/queue/list from {}", exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            // Parse pagination parameters.
            Map<String, String> queryParams = parseQuery(exchange.getRequestURI());
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
            String template = readResourceFile("queue-list-ui.html");

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

            sendHtml(exchange, 200, html);
        } catch (Exception e) {
            log.error("Error processing /client/queue/list: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Checks if the request method matches expected and if the exchange is authenticated.
     * Sends appropriate error responses if checks fail.
     *
     * @param exchange HTTP exchange.
     * @param expectedMethod Expected HTTP method (e.g., "POST", "GET").
     * @return true if method and auth check pass, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    boolean checkMethodAndAuth(HttpExchange exchange, String expectedMethod) throws IOException {
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-{} request: method={}", expectedMethod, exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed");
            return false;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return false;
        }

        return true;
    }

    /**
     * Handles <b>GET /logs</b> requests.
     * <p>Searches current and previous log4j2 log files for lines matching a query string.
     * <p>Supports only GET requests with query parameter "query" or "q".
     * <p>Returns usage message if no query parameter is provided.
     */
    private void handleLogs(HttpExchange exchange) throws IOException {
        // Only GET is supported, reject anything else.
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-GET request to /logs: method={}", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        log.debug("GET /logs from {}", exchange.getRemoteAddress());
        try {
            Map<String, String> queryParams = parseQuery(exchange.getRequestURI());
            String query = queryParams.get("query");
            if (query == null || query.isBlank()) {
                query = queryParams.get("q");
            }

            if (query == null || query.isBlank()) {
                String usage = "Usage: /logs?query=<search-term>\n" +
                        "       /logs?q=<search-term>\n\n" +
                        "Searches the current and previous log4j2 log files for lines matching the query string.\n" +
                        "Returns matching lines as plain text.\n";
                sendText(exchange, 200, usage);
                return;
            }

            // Use LogsHandler to search logs
            LogsHandler logsHandler = new LogsHandler();
            String results = logsHandler.searchLogs(query);
            sendText(exchange, 200, results);
        } catch (LogsHandler.LogsSearchException e) {
            log.error("Error searching logs: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error processing /logs: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Storage browser handler.
     * <p>Directory listings are returned as HTML (clickable links). Individual .eml files are served as text/plain.
     * <p>If Accept contains application/json, directory listings and .eml files are returned as JSON payloads.
     */
    private void handleStore(HttpExchange exchange) throws IOException {
        log.debug("Handling store request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        String method = exchange.getRequestMethod();
        boolean jsonResponse = acceptsJson(exchange);

        // Parse and validate the requested path
        String base = storagePathOverride != null
                ? storagePathOverride
                : Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        Path basePath = Paths.get(base).toAbsolutePath().normalize();

        String decoded = parseStorePath(exchange.getRequestURI().getPath());
        if (decoded.contains("..")) {
            if (jsonResponse) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
            } else {
                sendText(exchange, 403, "Forbidden");
            }
            return;
        }

        Path target = decoded.isEmpty() ? basePath : basePath.resolve(decoded).toAbsolutePath().normalize();
        if (!target.startsWith(basePath)) {
            if (jsonResponse) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
            } else {
                sendText(exchange, 403, "Forbidden");
            }
            return;
        }

        List<String> segments = decoded.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.stream(decoded.split("/"))
                .filter(s -> s != null && !s.isBlank())
                .toList());

        // Store mutation and metadata endpoints.
        if (!"GET".equalsIgnoreCase(method)) {
            handleStoreMutation(exchange, method, basePath, segments);
            return;
        }
        if (isFolderPropertiesPath(segments)) {
            handleStoreFolderProperties(exchange, basePath, segments);
            return;
        }

        // Serve individual .eml files as text/plain
        if (Files.isRegularFile(target)) {
            serveEmlFile(exchange, target, jsonResponse, decoded);
            return;
        }

        // Generate directory listing
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            if (jsonResponse) {
                sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            } else {
                sendText(exchange, 404, "Not Found");
            }
            return;
        }

        if (jsonResponse) {
            Map<String, Object> response = new HashMap<>();
            response.put("path", "/" + decoded);
            response.put("items", buildStoreItems(target, decoded));
            sendJson(exchange, 200, gson.toJson(response));
            return;
        }

        StorageDirectoryListing listing = new StorageDirectoryListing("/store");
        String template = readResourceFile("store-browser-ui.html");
        String items = listing.generateItems(target, decoded);

        String html = template
                .replace("{{PATH}}", escapeHtml("/" + decoded))
                .replace("{{ITEMS}}", items);

        sendHtml(exchange, 200, html);
    }

    /**
     * Parses the store path from the request URI.
     */
    private String parseStorePath(String requestPath) {
        String prefix = "/store";
        String rel = requestPath.length() > prefix.length() ? requestPath.substring(prefix.length()) : "/";

        String decoded = URLDecoder.decode(rel, StandardCharsets.UTF_8).replace('\\', '/');

        // Normalize to a clean relative path to avoid double slashes in generated links.
        while (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        while (decoded.endsWith("/") && !decoded.isEmpty()) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    private void handleStoreMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        if (segments.size() < 3) {
            sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        String scope = segments.get(2);
        if ("folders".equals(scope)) {
            handleStoreFolderMutation(exchange, method, basePath, segments);
            return;
        }
        if ("messages".equals(scope)) {
            handleStoreMessageMutation(exchange, method, basePath, segments);
            return;
        }
        if ("drafts".equals(scope)) {
            handleStoreDraftMutation(exchange, method, basePath, segments);
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private boolean isFolderPropertiesPath(List<String> segments) {
        return segments.size() >= 5
                && "folders".equals(segments.get(2))
                && "properties".equals(segments.get(segments.size() - 1));
    }

    private Path resolveUserRoot(Path basePath, List<String> segments) throws IOException {
        if (segments.size() < 2) {
            throw new IOException("Missing domain/user path");
        }
        Path userRoot = basePath.resolve(segments.get(0)).resolve(segments.get(1)).toAbsolutePath().normalize();
        if (!userRoot.startsWith(basePath)) {
            throw new IOException("Forbidden");
        }
        return userRoot;
    }

    private Path resolveFolderPath(Path userRoot, String folderPath) throws IOException {
        String clean = folderPath == null ? "" : folderPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/") && !clean.isEmpty()) clean = clean.substring(0, clean.length() - 1);
        if (clean.contains("..")) throw new IOException("Forbidden");

        Path folder = clean.isEmpty() ? userRoot : userRoot.resolve(clean).toAbsolutePath().normalize();
        if (!folder.startsWith(userRoot)) throw new IOException("Forbidden");
        return folder;
    }

    private String joinSegments(List<String> segments, int fromInclusive, int toExclusive) {
        if (fromInclusive >= toExclusive || fromInclusive >= segments.size()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive && i < segments.size(); i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(segments.get(i));
        }
        return sb.toString();
    }

    private Map<String, Object> parseJsonBody(InputStream is) throws IOException {
        String body = readBody(is).trim();
        if (body.isBlank()) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new Gson().fromJson(body, Map.class);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            throw new IOException("Invalid JSON body");
        }
    }

    private void handleStoreFolderMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = resolveUserRoot(basePath, segments);

            // POST /store/{domain}/{user}/folders
            if ("POST".equalsIgnoreCase(method) && segments.size() == 3) {
                Map<String, Object> body = parseJsonBody(exchange.getRequestBody());
                String parent = String.valueOf(body.getOrDefault("parent", ""));
                String name = String.valueOf(body.getOrDefault("name", "")).trim();
                if (name.isBlank()) {
                    sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Folder name is required.\"}");
                    return;
                }
                Path folder = resolveFolderPath(userRoot, (parent.isBlank() ? "" : parent + "/") + name);
                Files.createDirectories(folder.resolve("new"));
                Files.createDirectories(folder.resolve("cur"));
                sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder created.\"}");
                return;
            }

            // POST /store/{domain}/{user}/folders/{folder}/copy|move
            if ("POST".equalsIgnoreCase(method) && segments.size() >= 5) {
                String action = segments.get(segments.size() - 1);
                String folderRel = joinSegments(segments, 3, segments.size() - 1);
                Path source = resolveFolderPath(userRoot, folderRel);
                if (!Files.exists(source) || !Files.isDirectory(source)) {
                    sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
                    return;
                }
                Map<String, Object> body = parseJsonBody(exchange.getRequestBody());

                if ("copy".equals(action)) {
                    String destinationParent = String.valueOf(body.getOrDefault("destinationParent", ""));
                    String newName = String.valueOf(body.getOrDefault("newName", source.getFileName().toString())).trim();
                    Path targetParent = resolveFolderPath(userRoot, destinationParent);
                    Files.createDirectories(targetParent);
                    Path target = targetParent.resolve(newName).toAbsolutePath().normalize();
                    copyRecursively(source, target);
                    sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder copied.\"}");
                    return;
                }

                if ("move".equals(action)) {
                    String destinationParent = String.valueOf(body.getOrDefault("destinationParent", ""));
                    Path targetParent = resolveFolderPath(userRoot, destinationParent);
                    Files.createDirectories(targetParent);
                    Path target = targetParent.resolve(source.getFileName()).toAbsolutePath().normalize();
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder moved.\"}");
                    return;
                }
            }

            // PATCH /store/{domain}/{user}/folders/{folder}
            if ("PATCH".equalsIgnoreCase(method) && segments.size() >= 4) {
                String folderRel = joinSegments(segments, 3, segments.size());
                Path source = resolveFolderPath(userRoot, folderRel);
                if (!Files.exists(source) || !Files.isDirectory(source)) {
                    sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
                    return;
                }
                Map<String, Object> body = parseJsonBody(exchange.getRequestBody());
                String newName = String.valueOf(body.getOrDefault("name", "")).trim();
                if (newName.isBlank()) {
                    sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Folder name is required.\"}");
                    return;
                }
                Path target = source.getParent().resolve(newName).toAbsolutePath().normalize();
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder renamed.\"}");
                return;
            }

            // DELETE /store/{domain}/{user}/folders/{folder}
            if ("DELETE".equalsIgnoreCase(method) && segments.size() >= 4) {
                String folderRel = joinSegments(segments, 3, segments.size());
                Path source = resolveFolderPath(userRoot, folderRel);
                if (!Files.exists(source) || !Files.isDirectory(source)) {
                    sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
                    return;
                }
                boolean hasFiles;
                try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
                    hasFiles = walk.anyMatch(Files::isRegularFile);
                }
                if (hasFiles) {
                    sendJson(exchange, 400, "{\"r\":0,\"msg\":\"Folder is not empty.\"}");
                    return;
                }
                deleteRecursively(source);
                sendJson(exchange, 200, "{\"r\":1,\"msg\":\"Folder removed.\"}");
                return;
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJson(exchange, 400, "{\"r\":0,\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        } catch (Exception e) {
            log.error("Store folder mutation failed: {}", e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleStoreFolderProperties(HttpExchange exchange, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = resolveUserRoot(basePath, segments);
            String folderRel = joinSegments(segments, 3, segments.size() - 1);
            Path folder = resolveFolderPath(userRoot, folderRel);
            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                sendJson(exchange, 404, "{\"r\":0,\"msg\":\"Folder not found.\"}");
                return;
            }

            long size = 0L;
            int total = 0;
            int unread = 0;
            try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    size += Files.size(file);
                    if (file.getFileName().toString().toLowerCase().endsWith(".eml")) {
                        total++;
                        if (file.toString().replace('\\', '/').contains("/new/")) {
                            unread++;
                        }
                    }
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("r", 1);
            response.put("size", size);
            response.put("unread", unread);
            response.put("total", total);
            sendJson(exchange, 200, gson.toJson(response));
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJson(exchange, 400, "{\"r\":0,\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            log.error("Store folder properties failed: {}", e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleStoreMessageMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        if (!"POST".equalsIgnoreCase(method) || segments.size() != 4) {
            sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        try {
            Path userRoot = resolveUserRoot(basePath, segments);
            String op = segments.get(3);
            Map<String, Object> body = parseJsonBody(exchange.getRequestBody());

            switch (op) {
                case "move" -> {
                    String fromFolder = String.valueOf(body.getOrDefault("fromFolder", "")).trim();
                    String toFolder = String.valueOf(body.getOrDefault("toFolder", "")).trim();
                    List<String> ids = toStringList(body.get("messageIds"));
                    int moved = moveMessages(userRoot, fromFolder, toFolder, ids);
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", moved > 0);
                    response.put("moved", moved);
                    sendJson(exchange, 200, gson.toJson(response));
                }
                case "read-status" -> {
                    String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
                    String action = String.valueOf(body.getOrDefault("action", "")).trim();
                    List<String> ids = toStringList(body.get("messageIds"));
                    int moved = updateReadStatus(userRoot, folder, action, ids);
                    sendJson(exchange, 200, "{\"moved\":" + moved + "}");
                }
                case "mark-all-read" -> {
                    String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
                    int moved = markAllRead(userRoot, folder);
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("moved", moved);
                    sendJson(exchange, 200, gson.toJson(response));
                }
                case "delete-all" -> {
                    String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
                    int deleted = deleteAllMessages(userRoot, folder);
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("deleted", deleted);
                    sendJson(exchange, 200, gson.toJson(response));
                }
                case "cleanup" -> {
                    String folder = String.valueOf(body.getOrDefault("folder", "")).trim();
                    int months = toInt(body.getOrDefault("months", 3), 3);
                    int affected = cleanupMessages(userRoot, folder, months);
                    Map<String, Object> response = new HashMap<>();
                    response.put("r", 1);
                    response.put("msg", "Cleanup complete.");
                    response.put("affected", affected);
                    sendJson(exchange, 200, gson.toJson(response));
                }
                default -> sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            log.error("Store message mutation failed: {}", e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleStoreDraftMutation(HttpExchange exchange, String method, Path basePath, List<String> segments) throws IOException {
        try {
            Path userRoot = resolveUserRoot(basePath, segments);
            Path draftsNew = userRoot.resolve(".Drafts").resolve("new").toAbsolutePath().normalize();
            if (!draftsNew.startsWith(userRoot)) throw new IOException("Forbidden");
            Files.createDirectories(draftsNew);

            // POST /store/{domain}/{user}/drafts
            if ("POST".equalsIgnoreCase(method) && segments.size() == 3) {
                String draftId = "draft-" + UUID.randomUUID() + ".eml";
                Path draftFile = draftsNew.resolve(draftId);
                byte[] draftBytes = readDraftBytes(exchange);
                Files.write(draftFile, draftBytes);

                Map<String, Object> response = new HashMap<>();
                response.put("r", 1);
                response.put("draftId", draftId);
                response.put("msg", "mail successfully saved.");
                sendJson(exchange, 200, gson.toJson(response));
                return;
            }

            // PUT/DELETE /store/{domain}/{user}/drafts/{draftId}
            if (segments.size() == 4) {
                String draftId = segments.get(3);
                Path draftFile = draftsNew.resolve(draftId).toAbsolutePath().normalize();
                if (!draftFile.startsWith(draftsNew)) throw new IOException("Forbidden");

                if ("PUT".equalsIgnoreCase(method)) {
                    byte[] draftBytes = readDraftBytes(exchange);
                    Files.write(draftFile, draftBytes);
                    Map<String, Object> response = new HashMap<>();
                    response.put("r", 1);
                    response.put("draftId", draftId);
                    response.put("msg", "mail successfully saved.");
                    sendJson(exchange, 200, gson.toJson(response));
                    return;
                }

                if ("DELETE".equalsIgnoreCase(method)) {
                    Files.deleteIfExists(draftFile);
                    Files.deleteIfExists(userRoot.resolve(".Drafts").resolve("cur").resolve(draftId));
                    Files.deleteIfExists(userRoot.resolve(".Drafts").resolve(draftId));
                    sendJson(exchange, 200, "{\"r\":1,\"msg\":\"mail successfully discarded.\"}");
                    return;
                }
            }

            // POST /store/{domain}/{user}/drafts/{draftId}/attachments
            if ("POST".equalsIgnoreCase(method) && segments.size() == 5 && "attachments".equals(segments.get(4))) {
                String draftId = segments.get(3);
                Path attachmentsDir = userRoot.resolve(".Drafts").resolve("attachments").resolve(draftId).toAbsolutePath().normalize();
                if (!attachmentsDir.startsWith(userRoot)) throw new IOException("Forbidden");
                Files.createDirectories(attachmentsDir);

                byte[] attachment = readUploadedEmlBytes(exchange);
                String name = normalizeUploadFileName(parseQuery(exchange.getRequestURI()).get("filename"));
                String attachmentId = "att-" + UUID.randomUUID() + "-" + name;
                Path file = attachmentsDir.resolve(attachmentId);
                Files.write(file, attachment);

                Map<String, Object> response = new HashMap<>();
                response.put("r", 1);
                response.put("f", List.of(attachmentId));
                sendJson(exchange, 200, gson.toJson(response));
                return;
            }

            // DELETE /store/{domain}/{user}/drafts/{draftId}/attachments/{attachmentId}
            if ("DELETE".equalsIgnoreCase(method) && segments.size() == 6 && "attachments".equals(segments.get(4))) {
                String draftId = segments.get(3);
                String attachmentId = segments.get(5);
                Path attachmentsDir = userRoot.resolve(".Drafts").resolve("attachments").resolve(draftId).toAbsolutePath().normalize();
                Path file = attachmentsDir.resolve(attachmentId).toAbsolutePath().normalize();
                if (!file.startsWith(attachmentsDir) || !attachmentsDir.startsWith(userRoot)) throw new IOException("Forbidden");
                Files.deleteIfExists(file);
                sendJson(exchange, 200, "{\"r\":1}");
                return;
            }
        } catch (IOException e) {
            if ("Forbidden".equals(e.getMessage())) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        } catch (Exception e) {
            log.error("Store draft mutation failed: {}", e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private int moveMessages(Path userRoot, String fromFolder, String toFolder, List<String> messageIds) throws IOException {
        Path from = resolveFolderPath(userRoot, fromFolder);
        Path toLeaf = resolveMaildirLeaf(resolveFolderPath(userRoot, toFolder), "new");
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

    private int updateReadStatus(Path userRoot, String folder, String action, List<String> messageIds) throws IOException {
        Path folderPath = resolveFolderPath(userRoot, folder);
        Path srcDir;
        Path dstDir;
        if ("read".equalsIgnoreCase(action)) {
            srcDir = resolveMaildirLeaf(folderPath, "new");
            dstDir = resolveMaildirLeaf(folderPath, "cur");
        } else if ("unread".equalsIgnoreCase(action)) {
            srcDir = resolveMaildirLeaf(folderPath, "cur");
            dstDir = resolveMaildirLeaf(folderPath, "new");
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

    private int markAllRead(Path userRoot, String folder) throws IOException {
        Path root = resolveFolderPath(userRoot, folder);
        Path src = resolveMaildirLeaf(root, "new");
        Path dst = resolveMaildirLeaf(root, "cur");
        Files.createDirectories(dst);
        if (!Files.isDirectory(src)) return 0;
        int moved = 0;
        try (java.util.stream.Stream<Path> list = Files.list(src)) {
            for (Path file : list.filter(Files::isRegularFile).toList()) {
                Files.move(file, dst.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
        }
        return moved;
    }

    private int deleteAllMessages(Path userRoot, String folder) throws IOException {
        Path root = resolveFolderPath(userRoot, folder);
        if (isMaildirLeaf(root)) {
            return deleteMessagesInDir(root);
        }
        int deleted = 0;
        deleted += deleteMessagesInDir(root);
        deleted += deleteMessagesInDir(root.resolve("new"));
        deleted += deleteMessagesInDir(root.resolve("cur"));
        return deleted;
    }

    private int cleanupMessages(Path userRoot, String folder, int months) throws IOException {
        Path root = resolveFolderPath(userRoot, folder);
        if (isMaildirLeaf(root)) {
            return cleanupMessagesInTree(root, months);
        }
        return cleanupMessagesInTree(root, months);
    }

    private int cleanupMessagesInTree(Path root, int months) throws IOException {
        Instant cutoff = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .minusMonths(Math.max(0, months))
                .toInstant();
        int affected = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
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

    private Path resolveMaildirLeaf(Path folderPath, String preferredLeaf) {
        if (isMaildirLeaf(folderPath)) {
            if (folderPath.getFileName() != null && preferredLeaf.equals(folderPath.getFileName().toString())) {
                return folderPath;
            }
            Path parent = folderPath.getParent();
            return parent == null ? folderPath : parent.resolve(preferredLeaf);
        }
        return folderPath.resolve(preferredLeaf);
    }

    private boolean isMaildirLeaf(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return "new".equals(name) || "cur".equals(name);
    }

    private int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

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

    private int deleteMessagesInDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int deleted = 0;
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().toLowerCase().endsWith(".eml")) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private Path findMessagePath(Path folderRoot, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        Path direct = folderRoot.resolve(messageId);
        if (Files.exists(direct)) return direct;

        if (isMaildirLeaf(folderRoot)) {
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

    private Path findMessagePathAnywhere(Path userRoot, String messageId) throws IOException {
        Path quick = findMessagePath(userRoot, messageId);
        if (quick != null) {
            return quick;
        }

        try (java.util.stream.Stream<Path> walk = Files.walk(userRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (messageId.equals(file.getFileName().toString())) {
                    return file;
                }
            }
        }
        return null;
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        if (!Files.exists(source)) throw new IOException("Source not found");
        try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
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

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private List<String> toStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private byte[] readDraftBytes(HttpExchange exchange) throws IOException {
        if (isRawUploadRequest(exchange)) {
            return readUploadedEmlBytes(exchange);
        }
        Map<String, Object> body = parseJsonBody(exchange.getRequestBody());
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

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Serves an individual .eml file as text/plain.
     */
    private void serveEmlFile(HttpExchange exchange, Path target, boolean jsonResponse, String decodedPath) throws IOException {
        if (!target.getFileName().toString().toLowerCase().endsWith(".eml")) {
            if (jsonResponse) {
                sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            } else {
                sendText(exchange, 404, "Not Found");
            }
            return;
        }

        if (jsonResponse) {
            Map<String, Object> response = new HashMap<>();
            response.put("path", "/store/" + decodedPath);
            response.put("name", target.getFileName().toString());
            response.put("size", Files.size(target));
            response.put("content", Files.readString(target, StandardCharsets.UTF_8));
            sendJson(exchange, 200, gson.toJson(response));
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
     * Escape minimal HTML characters
     */
    private String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '&' -> out.append("&amp;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }


    /**
     * Reads the full request body into a string using UTF-8 encoding.
     *
     * @param is Input stream of the request body.
     * @return String content of the request body.
     * @throws IOException If an I/O error occurs while reading.
     */
    String readBody(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String s = sb.toString();
            log.debug("Read request body ({} bytes)", s.getBytes(StandardCharsets.UTF_8).length);
            return s;
        }
    }

    /**
     * Parses the query string from a URI into a map of key-value pairs.
     *
     * @param uri Request URI.
     * @return Map of query parameter names to values.
     */
    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            log.debug("No query string in URI: {}", uri);
            return map;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = urlDecode(pair.substring(0, idx));
                String val = urlDecode(pair.substring(idx + 1));
                map.put(key, val);
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        log.debug("Parsed query params: {}", map);
        return map;
    }

    /**
     * URL-decodes a string using UTF-8 encoding.
     *
     * @param s Encoded string.
     * @return Decoded string.
     */
    private String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private CaseConfig buildCaseConfig(Map<?, ?> input) {
        CaseConfig caseConfig = new CaseConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = caseConfig.getMap();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return caseConfig;
    }

    private boolean isRawUploadRequest(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.contains("multipart/form-data")
                || ct.contains("message/rfc822")
                || ct.contains("application/octet-stream");
    }

    private void handleClientSendRawUpload(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String mail = query.getOrDefault("mail", "").trim();
        List<String> rcpts = parseRecipients(query.get("rcpt"));
        if (mail.isBlank() || rcpts.isEmpty()) {
            sendText(exchange, 400, "Missing required query parameters: mail and rcpt");
            return;
        }

        byte[] emlBytes = readUploadedEmlBytes(exchange);
        if (emlBytes.length == 0) {
            sendText(exchange, 400, "Empty upload body");
            return;
        }

        String uploadedPath = persistUploadedEml(mail, emlBytes, query.get("filename"));

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("mail", mail);
        envelope.put("rcpt", rcpts);
        envelope.put("file", uploadedPath);

        Map<String, Object> caseMap = new HashMap<>();
        caseMap.put("envelopes", List.of(envelope));

        String route = query.get("route");
        if (route != null && !route.isBlank()) {
            caseMap.put("route", route);
        }

        CaseConfig caseConfig = buildCaseConfig(caseMap);
        Client client;
        try {
            client = new Client()
                    .setSkip(true)
                    .send(caseConfig);
        } catch (AssertException e) {
            log.error("Raw upload send assertion error: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
            return;
        }

        Session session = client.getSession();
        log.info("/client/send raw upload completed: sessionUID={}, uploadedFile={}",
                session.getUID(), uploadedPath);
        sendJson(exchange, 200, gson.toJson(session));
    }

    private void handleClientQueueRawUpload(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String mail = query.getOrDefault("mail", "").trim();
        List<String> rcpts = parseRecipients(query.get("rcpt"));
        if (mail.isBlank() || rcpts.isEmpty()) {
            sendText(exchange, 400, "Missing required query parameters: mail and rcpt");
            return;
        }

        byte[] emlBytes = readUploadedEmlBytes(exchange);
        if (emlBytes.length == 0) {
            sendText(exchange, 400, "Empty upload body");
            return;
        }

        String uploadedPath = persistUploadedEml(mail, emlBytes, query.get("filename"));

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("mail", mail);
        envelope.put("rcpt", rcpts);
        envelope.put("file", uploadedPath);

        Map<String, Object> caseMap = new HashMap<>();
        caseMap.put("envelopes", List.of(envelope));

        String route = query.get("route");
        if (route != null && !route.isBlank()) {
            caseMap.put("route", route);
        }

        CaseConfig caseConfig = buildCaseConfig(caseMap);
        Session session = Factories.getSession();
        session.map(caseConfig);

        String protocolOverride = query.getOrDefault("protocol", Config.getServer().getRelay().getStringProperty("protocol", "ESMTP"));
        String mailboxOverride = query.getOrDefault("mailbox",
                Config.getServer().getDovecot().getSaveLda().getInboxFolder());

        RelaySession relaySession = new RelaySession(session)
                .setProtocol(protocolOverride)
                .setMailbox(mailboxOverride);

        QueueFiles.persistEnvelopeFiles(relaySession);

        PersistentQueue<RelaySession> queue = PersistentQueue.getInstance();
        queue.enqueue(relaySession);
        long size = queue.size();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "QUEUED");
        response.put("queueSize", size);
        response.put("session", session);
        response.put("uploadedFile", uploadedPath);
        sendJson(exchange, 202, gson.toJson(response));
    }

    private List<String> parseRecipients(String input) {
        List<String> out = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return out;
        }
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private byte[] readUploadedEmlBytes(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return body;
        }

        if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
            return body;
        }

        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            return body;
        }

        byte[] extracted = extractMultipartFileBytes(body, boundary);
        return extracted != null ? extracted : body;
    }

    private String extractMultipartBoundary(String contentType) {
        for (String token : contentType.split(";")) {
            String t = token.trim();
            if (t.toLowerCase().startsWith("boundary=")) {
                String boundary = t.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private byte[] extractMultipartFileBytes(byte[] multipartBody, String boundary) {
        Charset c = StandardCharsets.ISO_8859_1;
        String raw = new String(multipartBody, c);
        String delimiter = "--" + boundary;
        String[] parts = raw.split(Pattern.quote(delimiter));
        for (String part : parts) {
            if (part == null || part.isBlank() || "--".equals(part.trim())) {
                continue;
            }
            int split = part.indexOf("\r\n\r\n");
            if (split < 0) {
                continue;
            }

            String headers = part.substring(0, split).toLowerCase();
            if (!headers.contains("filename=") && !headers.contains("name=\"file\"")) {
                continue;
            }

            String content = part.substring(split + 4);
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            }
            if (content.endsWith("--")) {
                content = content.substring(0, content.length() - 2);
            }
            return content.getBytes(c);
        }
        return null;
    }

    private String persistUploadedEml(String sender, byte[] emlBytes, String requestedFileName) throws IOException {
        String basePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        boolean storageEnabled = Config.getServer().getStorage().getBooleanProperty("enabled", true);
        boolean localMailbox = Config.getServer().getStorage().getBooleanProperty("localMailbox", false);
        String outboundFolder = Config.getServer().getStorage().getStringProperty("outboundFolder", ".Sent/new");

        String fileName = normalizeUploadFileName(requestedFileName);

        Path targetDir;
        if (storageEnabled && localMailbox && sender.contains("@")) {
            String[] splits = sender.split("@", 2);
            String username = PathUtils.normalize(splits[0]);
            String domain = PathUtils.normalize(splits[1]);
            targetDir = Paths.get(basePath, domain, username, outboundFolder);
        } else {
            targetDir = Paths.get(basePath, "queue");
        }

        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);
        if (Files.exists(target)) {
            target = targetDir.resolve("eml-" + UUID.randomUUID() + ".eml");
        }

        Files.write(target, emlBytes);
        return target.toString();
    }

    private String normalizeUploadFileName(String requested) {
        String name = requested != null ? requested.trim() : "";
        if (name.isBlank()) {
            name = "eml-" + System.currentTimeMillis() + ".eml";
        }
        name = name.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        if (!name.toLowerCase().endsWith(".eml")) {
            name = name + ".eml";
        }
        if (name.contains("..")) {
            name = "eml-" + UUID.randomUUID() + ".eml";
        }
        return name;
    }

    /**
     * Handles users endpoints:
     * <ul>
     *   <li>GET /users</li>
     *   <li>GET /users/{username}/exists</li>
     *   <li>POST /users/authenticate</li>
     * </ul>
     */
    private void handleUsers(HttpExchange exchange) throws IOException {
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String base = "/users";
        String tail = path.length() > base.length() ? path.substring(base.length()) : "";

        if (tail.isEmpty() || "/".equals(tail)) {
            if (!"GET".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            handleUsersList(exchange);
            return;
        }

        if ("/authenticate".equals(tail)) {
            if (!"POST".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            handleUsersAuthenticate(exchange);
            return;
        }

        if (tail.endsWith("/exists")) {
            if (!"GET".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String username = tail.substring(1, tail.length() - "/exists".length());
            username = URLDecoder.decode(username, StandardCharsets.UTF_8);
            if (username.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Missing username\"}");
                return;
            }
            handleUsersExists(exchange, username);
            return;
        }

        sendText(exchange, 404, "Not Found");
    }

    private void handleUsersList(HttpExchange exchange) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        try {
            List<String> users = backend == UsersBackend.SQL ? getSqlUsers() : getConfigUsers();
            users.sort(String::compareToIgnoreCase);
            Map<String, Object> response = new HashMap<>();
            response.put("source", backend.name().toLowerCase());
            response.put("count", users.size());
            response.put("users", users);
            sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users list: {}", e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleUsersExists(HttpExchange exchange, String username) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        try {
            boolean exists = backend == UsersBackend.SQL ? sqlUserExists(username) : configUserExists(username);
            Map<String, Object> response = new HashMap<>();
            response.put("source", backend.name().toLowerCase());
            response.put("username", username);
            response.put("exists", exists);
            sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users/{}/exists: {}", username, e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void handleUsersAuthenticate(HttpExchange exchange) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        String body = readBody(exchange.getRequestBody());
        if (body.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        Map<String, Object> map;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Gson().fromJson(body, Map.class);
            map = parsed;
        } catch (Exception e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }
        String username = map != null && map.get("username") != null ? String.valueOf(map.get("username")).trim() : "";
        String password = map != null && map.get("password") != null ? String.valueOf(map.get("password")) : "";
        if (username.isBlank() || password.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing username or password\"}");
            return;
        }

        try {
            boolean authenticated = backend == UsersBackend.SQL
                    ? sqlAuthenticate(username, password)
                    : configAuthenticate(username, password);

            Map<String, Object> response = new HashMap<>();
            response.put("source", backend.name().toLowerCase());
            response.put("username", username);
            response.put("authenticated", authenticated);
            sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users/authenticate for {}: {}", username, e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private enum UsersBackend {
        CONFIG,
        SQL,
        NONE
    }

    private UsersBackend getUsersBackend() {
        if (Config.getServer().getDovecot().isAuthSqlEnabled()) {
            return UsersBackend.SQL;
        }
        // For integration endpoints, allow config-based users when a list exists,
        // even if listEnabled is false (dev/test convenience without SQL).
        if (Config.getServer().getUsers().isListEnabled()
                || !Config.getServer().getUsers().getList().isEmpty()) {
            return UsersBackend.CONFIG;
        }
        return UsersBackend.NONE;
    }

    private Map<String, Object> buildUsersBackendNotConfiguredError() {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Users API backend is not configured");
        error.put("authSqlEnabled", Config.getServer().getDovecot().isAuthSqlEnabled());
        error.put("usersListEnabled", Config.getServer().getUsers().isListEnabled());
        error.put("usersListCount", Config.getServer().getUsers().getList().size());
        return error;
    }

    private List<String> getConfigUsers() {
        List<String> users = new ArrayList<>();
        for (UserConfig user : Config.getServer().getUsers().getList()) {
            if (user.getName() != null && !user.getName().isBlank()) {
                users.add(user.getName());
            }
        }
        return users;
    }

    private boolean configUserExists(String username) {
        return Config.getServer().getUsers().getUser(username).isPresent();
    }

    private boolean configAuthenticate(String username, String password) {
        return Config.getServer().getUsers().getUser(username)
                .map(u -> password.equals(u.getPass()))
                .orElse(false);
    }

    private List<String> getSqlUsers() throws Exception {
        String usersQuery = Config.getServer().getDovecot().getAuthSqlUsersQuery();
        List<String> users = new ArrayList<>();
        try (Connection c = SharedDataSource.getDataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(usersQuery)) {
            while (rs.next()) {
                String user = rs.getString(1);
                if (user != null && !user.isBlank()) {
                    users.add(user);
                }
            }
        }
        return users;
    }

    private boolean sqlUserExists(String username) throws Exception {
        String userQuery = Config.getServer().getDovecot().getAuthSqlUserQuery();
        if (userQuery == null || userQuery.isBlank()) {
            userQuery = "SELECT email FROM users WHERE email = ?";
        }
        try (Connection c = SharedDataSource.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(userQuery)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean sqlAuthenticate(String username, String password) throws Exception {
        String authQuery = Config.getServer().getDovecot().getAuthSqlPasswordQuery();
        if (authQuery == null || authQuery.isBlank()) {
            authQuery = "SELECT (crypt(?, regexp_replace(password, '^\\{[^}]+\\}', '')) = regexp_replace(password, '^\\{[^}]+\\}', '')) AS ok FROM users WHERE email = ?";
        }

        try (Connection c = SharedDataSource.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(authQuery)) {
            ps.setString(1, password);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }

                Object ok = rs.getObject(1);
                if (ok instanceof Boolean) {
                    return (Boolean) ok;
                }
                if (ok != null) {
                    String value = String.valueOf(ok).trim();
                    return "1".equals(value) || "true".equalsIgnoreCase(value) || "t".equalsIgnoreCase(value);
                }
                return false;
            }
        }
    }

    private boolean acceptsJson(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.toLowerCase().contains("application/json");
    }

    private List<Map<String, Object>> buildStoreItems(Path targetPath, String relativePath) throws IOException {
        List<Path> children;
        try (java.util.stream.Stream<Path> stream = Files.list(targetPath)) {
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
                if (containsEmlFiles(child)) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("type", "dir");
                    item.put("name", name);
                    item.put("path", "/store/" + (relativePath.isEmpty() ? name : relativePath + "/" + name) + "/");
                    items.add(item);
                }
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

    private boolean isEmlFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".eml");
    }

    private boolean containsEmlFiles(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(this::isEmlFile);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Builds an HTML row for a relay session in the queue list.
     *
     * @param relaySession The relay session.
     * @param rowNumber The display row number (1-based).
     * @return HTML string for the row.
     */
    private String buildQueueRow(RelaySession relaySession, int rowNumber) {
        Session session = relaySession.getSession();
        List<MessageEnvelope> envs = session != null ? session.getEnvelopes() : null;
        int envCount = envs != null ? envs.size() : 0;

        // Recipients summary (first 5 unique, then +N).
        StringBuilder recipients = new StringBuilder();
        int added = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (envs != null) {
            for (MessageEnvelope env : envs) {
                if (env == null) continue;
                for (String r : env.getRcpts()) {
                    if (seen.add(r)) {
                        if (added > 0) recipients.append(", ");
                        recipients.append(escapeHtml(r));
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
                    files.append("<span title='").append(escapeHtml(f)).append("'>").append(escapeHtml(base)).append("</span>");
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
            rowTemplate = readResourceFile("queue-list-row.html");
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
                .replace("{{RELAY_UID}}", escapeHtml(relayUID))
                .replace("{{ROW_NUMBER}}", String.valueOf(rowNumber))
                .replace("{{SESSION_UID}}", escapeHtml(sessionUID))
                .replace("{{DATE}}", escapeHtml(session.getDate()))
                .replace("{{PROTOCOL}}", escapeHtml(relaySession.getProtocol()))
                .replace("{{RETRY_COUNT}}", String.valueOf(relaySession.getRetryCount()))
                .replace("{{LAST_RETRY}}", escapeHtml(lastRetry))
                .replace("{{ENVELOPES}}", String.valueOf(envCount))
                .replace("{{RECIPIENTS}}", recipients.toString())
                .replace("{{FILES}}", files.toString());
    }

    /**
     * Builds pagination controls HTML.
     *
     * @param currentPage Current page number (1-based).
     * @param totalPages Total number of pages.
     * @param limit Items per page.
     * @return HTML string for pagination controls.
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
