package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.config.server.EndpointConfig;
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
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *   <li><b>GET /store[/...]</b> — Browse local message storage. Directory listings are returned as HTML with clickable
 *       links; individual <code>.eml</code> files are returned as <code>text/plain</code>. Empty folders are ignored.</li>
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

    /**
     * Starts the API endpoint with endpoint configuration.
     *
     * @param config EndpointConfig containing port and authentication settings (authType, authValue, allowList).
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start(EndpointConfig config) throws IOException {
        // Initialize authentication handler.
        this.auth = new HttpAuth(config, "API");

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

            CaseConfig caseConfig = new CaseConfig(map);

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

            CaseConfig caseConfig = new CaseConfig(map);

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
     */
    private void handleStore(HttpExchange exchange) throws IOException {
        log.debug("Handling store request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        // Parse and validate the requested path
        String base = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        Path basePath = Paths.get(base).toAbsolutePath().normalize();

        String decoded = parseStorePath(exchange.getRequestURI().getPath());
        if (decoded.contains("..")) {
            sendText(exchange, 403, "Forbidden");
            return;
        }

        Path target = decoded.isEmpty() ? basePath : basePath.resolve(decoded).toAbsolutePath().normalize();
        if (!target.startsWith(basePath)) {
            sendText(exchange, 403, "Forbidden");
            return;
        }

        // Serve individual .eml files as text/plain
        if (Files.isRegularFile(target)) {
            serveEmlFile(exchange, target);
            return;
        }

        // Generate directory listing
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            sendText(exchange, 404, "Not Found");
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

        String decoded = URLDecoder.decode(rel, StandardCharsets.UTF_8);
        return decoded.startsWith("/") ? decoded.substring(1) : decoded;
    }

    /**
     * Serves an individual .eml file as text/plain.
     */
    private void serveEmlFile(HttpExchange exchange, Path target) throws IOException {
        if (!target.getFileName().toString().toLowerCase().endsWith(".eml")) {
            sendText(exchange, 404, "Not Found");
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
