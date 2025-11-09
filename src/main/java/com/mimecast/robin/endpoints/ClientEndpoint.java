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
import com.mimecast.robin.queue.RelayQueueCron;
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
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client case submission endpoint.
 *
 * <p>Starts a lightweight HTTP server to accept JSON/JSON5 case definitions
 * (either as raw JSON in the body or by providing a file path) and executes the client.
 *
 * <p>Endpoint:
 * <ul>
 *   <li><b>GET /</b> — Serves a simple HTML landing page documenting available client endpoints.</li>
 *   <li><b>POST /client/send</b> — Accepts either a JSON/JSON5 payload describing a case or a query parameter
 *       <code>path</code> that points to a case file on disk. It executes the SMTP client with the supplied case and
 *       responds with the final {@link Session} serialized as JSON.</li>
 *   <li><b>POST /client/queue</b> — Same inputs as <code>/client/send</code>, but instead of sending immediately, it
 *       enqueues the built {@link Session} as a {@link RelaySession} into the persistent relay queue.</li>
 *   <li><b>GET /client/queue-list</b> — Lists the current relay queue contents in a simple HTML table.</li>
 *   <li><b>GET /logs</b> — Searches log files for lines matching a query string. Supports text/plain GET requests only.
 *       Returns usage message if no query parameter is set. Searches current and previous log4j2 log files.</li>
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
public class ClientEndpoint {
    private static final Logger log = LogManager.getLogger(ClientEndpoint.class);

    /**
     * Gson instance used for serializing responses with an exclusion strategy
     * tailored to remove large or sensitive fields.
     */
    private Gson gson;

    /**
     * HTTP Authentication handler for securing API endpoints.
     */
    private HttpAuth auth;

    /**
     * Starts the client submission endpoint with endpoint configuration.
     *
     * @param config EndpointConfig containing port and authentication settings (authType, authValue, allowList).
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start(EndpointConfig config) throws IOException {
        // Initialize authentication handler.
        this.auth = new HttpAuth(config, "Client API");

        // Build a Gson serializer that excludes fields we don't want to expose.
        gson = new GsonBuilder()
                .addSerializationExclusionStrategy(new GsonExclusionStrategy())
                .setPrettyPrinting()
                .create();

        // Bind the HTTP server to the configured API port.
        int apiPort = config.getPort(8090);
        HttpServer server = HttpServer.create(new InetSocketAddress(apiPort), 10);

        // Register endpoints.

        // Landing page for client endpoint discovery.
        server.createContext("/", this::handleLandingPage);

        // Main endpoint that triggers a Client.send(...) run for the supplied case.
        server.createContext("/client/send", this::handleClientSend);

        // Queue endpoint that enqueues a RelaySession for later delivery.
        server.createContext("/client/queue", this::handleClientQueue);

        // Queue listing endpoint.
        server.createContext("/client/queue-list", this::handleQueueList);

        // Logs search endpoint.
        server.createContext("/logs", this::handleLogs);

        // Liveness endpoint for client API.
        server.createContext("/health", exchange -> sendJson(exchange, 200, "{\"status\":\"UP\"}"));

        // Start the embedded server on a background thread.
        new Thread(server::start).start();
        log.info("Landing available at http://localhost:{}/", apiPort);
        log.info("Submission endpoint available at http://localhost:{}/client/send", apiPort);
        log.info("Queue endpoint available at http://localhost:{}/client/queue", apiPort);
        log.info("Queue list available at http://localhost:{}/client/queue-list", apiPort);
        log.info("Logs available at http://localhost:{}/logs", apiPort);
        log.info("Health available at http://localhost:{}/health", apiPort);
        if (auth.isAuthEnabled()) {
            log.info("Authentication is enabled for client API endpoint");
        }
    }

    /**
     * Serves a simple HTML landing page that documents available client endpoints.
     */
    private void handleLandingPage(HttpExchange exchange) throws IOException {
        log.debug("Handling landing page request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            String response = readResourceFile("client-endpoints-ui.html");
            sendHtml(exchange, 200, response);
            log.debug("Landing page served successfully");
        } catch (IOException e) {
            log.error("Could not read client-endpoints-ui.html", e);
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
            String mailboxOverride = query.getOrDefault("mailbox", Config.getServer().getRelay().getStringProperty("mailbox"));
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
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
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
     */
    private void handleQueueList(HttpExchange exchange) throws IOException {
        log.debug("GET /client/queue-list from {}", exchange.getRemoteAddress());

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
            List<RelaySession> items = queue.snapshot();

            // Load HTML template from resources.
            String template = readResourceFile("queue-list-ui.html");

            // Build only the dynamic rows HTML.
            StringBuilder rows = new StringBuilder(Math.max(8192, items.size() * 256));
            for (int i = 0; i < items.size(); i++) {
                RelaySession relaySession = items.get(i);
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

                rows.append("<tr>")
                        .append("<td class='nowrap'>").append(i + 1).append("</td>")
                        .append("<td class='mono'>").append(escapeHtml(session != null ? session.getUID() : "-")).append("</td>")
                        .append("<td>").append(escapeHtml(session.getDate())).append("</td>")
                        .append("<td>").append(escapeHtml(relaySession.getProtocol())).append("</td>")
                        .append("<td>").append(relaySession.getRetryCount()).append("</td>")
                        .append("<td class='nowrap'>").append(escapeHtml(lastRetry)).append("</td>")
                        .append("<td>").append(envCount).append("</td>")
                        .append("<td>").append(recipients).append("</td>")
                        .append("<td>").append(files).append("</td>")
                        .append("</tr>");
            }

            String html = template
                    .replace("{{TOTAL}}", String.valueOf(items.size()))
                    .replace("{{ROWS}}", rows.toString());

            sendHtml(exchange, 200, html);
        } catch (Exception e) {
            log.error("Error processing /client/queue-list: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
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

            log.info("Searching logs for query: '{}'", query);
            StringBuilder results = new StringBuilder();
            int matchCount = 0;

            // Get log file pattern from log4j2 configuration
            String logFilePattern = getLogFilePatternFromLog4j2();
            if (logFilePattern == null) {
                log.error("Could not determine log file pattern from log4j2 configuration");
                sendText(exchange, 500, "Could not determine log file location from log4j2 configuration\n");
                return;
            }

            // Extract date format from the log file pattern
            String dateFormat = extractDateFormatFromPattern(logFilePattern);
            if (dateFormat == null) {
                log.error("Could not extract date format from log file pattern: {}", logFilePattern);
                sendText(exchange, 500, "Could not extract date format from log file pattern\n");
                return;
            }

            // Get current date and yesterday's date for log file names
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);

            // Build log file paths using the pattern from log4j2
            String datePatternPlaceholder = "%d{" + dateFormat + "}";
            String todayLogFile = logFilePattern.replace(datePatternPlaceholder, today.format(formatter));
            String yesterdayLogFile = logFilePattern.replace(datePatternPlaceholder, yesterday.format(formatter));

            log.debug("Searching log files: today={}, yesterday={}", todayLogFile, yesterdayLogFile);

            // Search today's log file
            matchCount += searchLogFile(todayLogFile, query, results);

            // Search yesterday's log file if it exists
            matchCount += searchLogFile(yesterdayLogFile, query, results);

            // Return just the results without additional text
            sendText(exchange, 200, results.toString());
            log.debug("Logs search completed: query='{}', matches={}", query, matchCount);
        } catch (Exception e) {
            log.error("Error processing /logs: {}", e.getMessage());
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Gets the log file pattern from log4j2 configuration by examining appenders.
     *
     * @return The log file pattern (e.g., "/var/log/robin-%d{yyyyMMdd}.log") or null if not found.
     */
    private String getLogFilePatternFromLog4j2() {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            
            // Iterate through all appenders to find a RollingFileAppender
            for (Map.Entry<String, Appender> entry : config.getAppenders().entrySet()) {
                Appender appender = entry.getValue();
                if (appender instanceof RollingFileAppender) {
                    RollingFileAppender rollingFileAppender = (RollingFileAppender) appender;
                    String filePattern = rollingFileAppender.getFilePattern();
                    if (filePattern != null && !filePattern.isBlank()) {
                        log.debug("Found log file pattern from appender '{}': {}", entry.getKey(), filePattern);
                        return filePattern;
                    }
                }
            }
            log.warn("No RollingFileAppender found in log4j2 configuration");
        } catch (Exception e) {
            log.error("Error reading log4j2 configuration: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Extracts the date format from a log4j2 file pattern.
     * <p>For example, extracts "yyyyMMdd" from "/var/log/robin-%d{yyyyMMdd}.log"
     *
     * @param filePattern The log file pattern from log4j2 configuration.
     * @return The date format string (e.g., "yyyyMMdd") or null if not found.
     */
    private String extractDateFormatFromPattern(String filePattern) {
        if (filePattern == null || filePattern.isBlank()) {
            return null;
        }
        
        // Look for %d{...} pattern
        int startIndex = filePattern.indexOf("%d{");
        if (startIndex == -1) {
            log.debug("No date pattern found in file pattern: {}", filePattern);
            return null;
        }
        
        int endIndex = filePattern.indexOf("}", startIndex);
        if (endIndex == -1) {
            log.debug("Malformed date pattern in file pattern: {}", filePattern);
            return null;
        }
        
        String dateFormat = filePattern.substring(startIndex + 3, endIndex);
        log.debug("Extracted date format '{}' from pattern '{}'", dateFormat, filePattern);
        return dateFormat;
    }

    /**
     * Searches a log file for lines matching the query string.
     *
     * @param logFilePath Path to the log file.
     * @param query Query string to search for.
     * @param results StringBuilder to append matching lines to.
     * @return Number of matches found.
     */
    private int searchLogFile(String logFilePath, String query, StringBuilder results) {
        Path path = Paths.get(logFilePath);
        if (!Files.exists(path)) {
            log.debug("Log file does not exist: {}", logFilePath);
            return 0;
        }

        int matches = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(query)) {
                    results.append(line).append("\n");
                    matches++;
                }
            }
            log.debug("Searched log file: {}, found {} matches", logFilePath, matches);
        } catch (IOException e) {
            log.error("Error reading log file {}: {}", logFilePath, e.getMessage());
        }
        return matches;
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
     * Sends a JSON response with the specified HTTP status code.
     *
     * @param exchange HTTP exchange.
     * @param code     HTTP status code.
     * @param json     JSON payload.
     * @throws IOException If an I/O error occurs.
     */
    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.debug("Sent JSON response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Sends a HTML response with the specified HTTP status code.
     *
     * @param exchange HTTP exchange.
     * @param code     HTTP status code.
     * @param html     HTML payload.
     * @throws IOException If an I/O error occurs.
     */
    private void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.trace("Sent HTML response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Sends a plain text response with the specified HTTP status code.
     *
     * @param exchange HTTP exchange.
     * @param code     HTTP status code.
     * @param text     Plain text payload.
     * @throws IOException If an I/O error occurs.
     */
    private void sendText(HttpExchange exchange, int code, String text) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.debug("Sent text response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Reads the full request body into a string using UTF-8 encoding.
     *
     * @param is Input stream of the request body.
     * @return String content of the request body.
     * @throws IOException If an I/O error occurs while reading.
     */
    private String readBody(InputStream is) throws IOException {
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
     * Reads a resource file from the classpath into a string.
     *
     * @param path The resource path (e.g., "client-endpoints-ui.html").
     * @return The file contents as a string.
     * @throws IOException If the resource cannot be found or read.
     */
    private String readResourceFile(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                String s = sb.toString();
                log.debug("Read resource '{}', bytes={}", path, s.getBytes(StandardCharsets.UTF_8).length);
                return s;
            }
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
}
