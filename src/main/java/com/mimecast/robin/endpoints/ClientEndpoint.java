package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
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
 *   <li><b>GET /client/health</b> — Simple liveness endpoint returning HTTP 200 with <code>{"status":"UP"}</code>.</li>
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
     * Starts the client submission endpoint on the configured API port.
     *
     * <p>Port is read from {@code server.json5} (property: {@code apiPort}).
     * If not present, falls back to {@code 8090}.
     *
     * @throws IOException If an I/O error occurs during server startup.
     */
    public void start() throws IOException {
        // Build a Gson serializer that excludes fields we don't want to expose.
        gson = new GsonBuilder()
                .addSerializationExclusionStrategy(new GsonExclusionStrategy())
                .setPrettyPrinting()
                .create();

        // Bind the HTTP server to the configured API port.
        int apiPort = getApiPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(apiPort), 10);

        // Register endpoints.

        // Landing page for client endpoint discovery.
        server.createContext("/", this::handleLandingPage);

        // Main endpoint that triggers a Client.send(...) run for the supplied case.
        server.createContext("/client/send", this::handleClientSend);

        // Queue endpoint that enqueues a RelaySession for later delivery.
        server.createContext("/client/queue", this::handleClientQueue);

        // New: Queue listing endpoint.
        server.createContext("/client/queue-list", this::handleQueueList);

        // Liveness endpoint for client API.
        server.createContext("/client/health", exchange -> sendJson(exchange, 200, "{\"status\":\"UP\"}"));

        // Start the embedded server on a background thread.
        new Thread(server::start).start();
        log.info("Landing available at http://localhost:{}/", apiPort);
        log.info("Submission endpoint available at http://localhost:{}/client/send", apiPort);
        log.info("Queue endpoint available at http://localhost:{}/client/queue", apiPort);
        log.info("Queue list available at http://localhost:{}/client/queue-list", apiPort);
        log.info("Health available at http://localhost:{}/client/health", apiPort);
    }

    /**
     * Serves a simple HTML landing page that documents available client endpoints.
     */
    private void handleLandingPage(HttpExchange exchange) throws IOException {
        log.debug("Handling landing page request: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
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
     * Resolves the API port from configuration (server.json5 -> apiPort) with a default fallback.
     *
     * @return Port number to bind for the client API.
     */
    private int getApiPort() {
        try {
            // Reflective access keeps compatibility if this method is absent on older configs.
            return Config.getServer().getClass()
                    .getMethod("getApiPort")
                    .invoke(Config.getServer()) instanceof Integer
                    ? (Integer) Config.getServer().getClass().getMethod("getApiPort").invoke(Config.getServer())
                    : 8090;
        } catch (Exception e) {
            // Fallback if method not present (older configs)
            log.warn("ServerConfig.getApiPort not found, using default 8090");
            return 8090;
        }
    }

    /**
     * Handles <b>POST /client/send</b> requests.
     *
     * <p>Supports two modes of input:
     * <ol>
     *   <li><b>Query param</b>: <code>?path=/path/to/case.json5</code> — loads the case from disk and executes it.</li>
     *   <li><b>Request body</b>: raw JSON/JSON5 describing the case — parsed and executed in-memory.</li>
     * </ol>
     *
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

        log.info("POST /client/send from {}", exchange.getRemoteAddress());
        try {
            // Parse query parameters (e.g., ?path=...).
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            log.debug("/client/send query params: {}", query);
            String pathParam = query.get("path");

            Session session;
            if (pathParam != null && !pathParam.isBlank()) {
                // File path mode: execute Client.send(<path>).
                String casePath = pathParam.trim();
                log.debug("Using case file path: {}", casePath);
                if (!PathUtils.isFile(casePath)) {
                    log.info("/client/send invalid case file path: {}", casePath);
                    sendText(exchange, 400, "Invalid case file path");
                    return;
                }

                // Create a new client instance and execute the case from path.
                Client client = new Client()
                        .setSkip(true) // Skip assertions for API runs.
                        .send(casePath);

                session = client.getSession();
                log.info("/client/send completed from file: sessionUID={}, envelopes={}",
                        session.getUID(), session.getEnvelopes() != null ? session.getEnvelopes().size() : 0);
            } else {
                // Body mode: accept a raw JSON/JSON5 payload describing the case.
                String body = readBody(exchange.getRequestBody());
                if (body.isBlank()) {
                    log.info("/client/send empty request body");
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
                    log.info("/client/send invalid JSON body");
                    sendText(exchange, 400, "Invalid JSON body");
                    return;
                }

                CaseConfig caseConfig = new CaseConfig(map);

                // Create a new client instance and execute the case in-memory.
                Client client = new Client()
                        .setSkip(true) // Skip assertions for API runs.
                        .send(caseConfig);

                session = client.getSession();
                log.info("/client/send completed from body: sessionUID={}, envelopes={}",
                        session.getUID(), session.getEnvelopes() != null ? session.getEnvelopes().size() : 0);
            }

            // Serialize session to JSON using the filtered Gson (excludes magic/savedResults/stream/bytes).
            String response = gson.toJson(session);
            sendJson(exchange, 200, response);
            log.debug("/client/send responded 200, bytes={}", response.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            // Any unexpected exception is reported as a 500 with a brief message.
            log.error("Error processing /client/send: {}", e.getMessage(), e);
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles <b>POST /client/queue</b> requests.
     *
     * <p>Supports two modes of input, same as /client/send:
     * <ol>
     *   <li><b>Query param</b>: <code>?path=/path/to/case.json5</code> — loads the case from disk and maps a session.</li>
     *   <li><b>Request body</b>: raw JSON/JSON5 describing the case — parsed and mapped in-memory.</li>
     * </ol>
     *
     * <p>On success, enqueues a {@link RelaySession} for later delivery and returns a JSON confirmation with
     * the filtered {@link Session} plus queue size.
     */
    private void handleClientQueue(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-POST request to /client/queue: method={}", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        log.info("POST /client/queue from {}", exchange.getRemoteAddress());
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            log.debug("/client/queue query params: {}", query);
            String pathParam = query.get("path");

            // Optional overrides via query params.
            String protocolOverride = query.getOrDefault("protocol", Config.getServer().getRelay().getStringProperty("protocol", "ESMTP"));
            String mailboxOverride = query.getOrDefault("mailbox", Config.getServer().getRelay().getStringProperty("mailbox"));
            log.debug("/client/queue overrides: protocol={}, mailbox={}", protocolOverride, mailboxOverride);

            CaseConfig caseConfig;
            if (pathParam != null && !pathParam.isBlank()) {
                String casePath = pathParam.trim();
                log.debug("Using case file path: {}", casePath);
                if (!PathUtils.isFile(casePath)) {
                    log.info("/client/queue invalid case file path: {}", casePath);
                    sendText(exchange, 400, "Invalid case file path");
                    return;
                }
                caseConfig = new CaseConfig(casePath);
            } else {
                String body = readBody(exchange.getRequestBody());
                if (body.isBlank()) {
                    log.info("/client/queue empty request body");
                    sendText(exchange, 400, "Empty request body");
                    return;
                }
                log.debug("/client/queue body length: {} bytes", body.getBytes(StandardCharsets.UTF_8).length);
                String processed = Magic.streamMagicReplace(body);
                @SuppressWarnings("rawtypes")
                Map map = new Gson().fromJson(processed, Map.class);
                if (map == null || map.isEmpty()) {
                    log.info("/client/queue invalid JSON body");
                    sendText(exchange, 400, "Invalid JSON body");
                    return;
                }
                caseConfig = new CaseConfig(map);
            }

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
            log.error("Error processing /client/queue: {}", e.getMessage(), e);
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Lists the relay queue contents in a simple HTML table.
     */
    private void handleQueueList(HttpExchange exchange) throws IOException {
        log.debug("GET /client/queue-list from {}", exchange.getRemoteAddress());
        try {
            PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(RelayQueueCron.QUEUE_FILE);
            List<RelaySession> items = queue.snapshot();

            // Load HTML template from resources.
            String template = readResourceFile("queue-list-ui.html");

            // Build only the dynamic rows HTML.
            StringBuilder rows = new StringBuilder(Math.max(8192, items.size() * 256));
            for (int i = 0; i < items.size(); i++) {
                RelaySession rs = items.get(i);
                Session s = rs.getSession();
                List<MessageEnvelope> envs = s != null ? s.getEnvelopes() : null;
                int envCount = envs != null ? envs.size() : 0;

                // Recipients summary (first 5 unique, then +N)
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

                // Files summary (first 5 base names with tooltip of full path)
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

                String lastRetry = rs.getLastRetryTime() > 0 ? rs.getLastRetryDate() : "-";

                rows.append("<tr>")
                        .append("<td class='nowrap'>").append(i + 1).append("</td>")
                        .append("<td class='mono'>").append(escapeHtml(s != null ? s.getUID() : "-")).append("</td>")
                        .append("<td>").append(escapeHtml(rs.getProtocol())).append("</td>")
                        .append("<td>").append(escapeHtml(rs.getMailbox())).append("</td>")
                        .append("<td>").append(rs.getRetryCount()).append("</td>")
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
            log.error("Error processing /client/queue-list: {}", e.getMessage(), e);
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /** Escape minimal HTML characters */
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
