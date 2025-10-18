package com.mimecast.robin.endpoints;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Config;
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
import java.util.HashMap;
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
                .addSerializationExclusionStrategy(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        // Exclude heavy or sensitive fields from Session
                        if (f.getDeclaringClass() == Session.class) {
                            String name = f.getName();
                            return "magic".equals(name) || "savedResults".equals(name);
                        }
                        // Exclude binary fields from MessageEnvelope
                        if (f.getDeclaringClass() == MessageEnvelope.class) {
                            String name = f.getName();
                            return "stream".equals(name) || "bytes".equals(name);
                        }
                        return false;
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> clazz) {
                        return false;
                    }
                })
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
        // Liveness endpoint for client API.
        server.createContext("/client/health", exchange -> sendJson(exchange, 200, "{\"status\":\"UP\"}"));

        // Start the embedded server on a background thread.
        new Thread(server::start).start();
        log.info("Landing available at http://localhost:{}/", apiPort);
        log.info("Submission endpoint available at http://localhost:{}/client/send", apiPort);
        log.info("Health available at http://localhost:{}/health", apiPort);
    }

    /**
     * Serves a simple HTML landing page that documents available client endpoints.
     */
    private void handleLandingPage(HttpExchange exchange) throws IOException {
        try {
            String response = readResourceFile("client-endpoints-ui.html");
            sendHtml(exchange, 200, response);
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
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            // Parse query parameters (e.g., ?path=...).
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String pathParam = query.get("path");

            Session session;
            if (pathParam != null && !pathParam.isBlank()) {
                // File path mode: execute Client.send(<path>).
                String casePath = pathParam.trim();
                if (!PathUtils.isFile(casePath)) {
                    sendText(exchange, 400, "Invalid case file path");
                    return;
                }

                // Create a new client instance and execute the case from path.
                Client client = new Client();
                client.send(casePath);
                session = client.getSession();
            } else {
                // Body mode: accept a raw JSON/JSON5 payload describing the case.
                String body = readBody(exchange.getRequestBody());
                if (body.isBlank()) {
                    sendText(exchange, 400, "Empty request body");
                    return;
                }

                // Apply magic replacements similar to how file-based configs are processed.
                String processed = Magic.streamMagicReplace(body);

                // Parse into a Map and build CaseConfig.
                @SuppressWarnings("rawtypes")
                Map map = new Gson().fromJson(processed, Map.class);
                if (map == null || map.isEmpty()) {
                    sendText(exchange, 400, "Invalid JSON body");
                    return;
                }

                CaseConfig caseConfig = new CaseConfig(map);

                // Create a new client instance and execute the case in-memory.
                Client client = new Client();
                client.send(caseConfig);
                session = client.getSession();
            }

            // Serialize session to JSON using the filtered Gson (excludes magic/savedResults/stream/bytes).
            String response = gson.toJson(session);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            // Any unexpected exception is reported as a 500 with a brief message.
            log.error("Error processing /client/send: {}", e.getMessage(), e);
            sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
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
            return sb.toString();
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
                return sb.toString();
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
        if (query == null || query.isEmpty()) return map;
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
