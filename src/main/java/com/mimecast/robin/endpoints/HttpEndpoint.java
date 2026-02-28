package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Abstract base class for HTTP endpoints serving both API and service monitoring capabilities.
 *
 * <p>Provides common functionality including:
 * <ul>
 *   <li>HTTP server initialization with configurable port and authentication</li>
 *   <li>Response generation utilities for JSON, HTML, plain text, and binary content</li>
 *   <li>Resource file loading from classpath</li>
 *   <li>Favicon serving</li>
 * </ul>
 */
public abstract class HttpEndpoint {
    private static final Logger log = LogManager.getLogger(HttpEndpoint.class);

    /**
     * HTTP Authentication handler for securing endpoints.
     */
    protected HttpAuth auth;

    /**
     * Embedded HTTP server instance.
     */
    protected HttpServer server;

    /**
     * Starts the HTTP endpoint with the given configuration.
     *
     * @param config EndpointConfig containing port and authentication settings.
     * @throws IOException If an I/O error occurs during server startup.
     */
    public abstract void start(EndpointConfig config) throws IOException;

    /**
     * Handles requests for the favicon.ico file.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    protected void handleFavicon(HttpExchange exchange) throws IOException {
        log.trace("Handling /favicon.ico: method={}, uri={}, remote={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress());
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("favicon.ico")) {
            if (is == null) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            byte[] faviconBytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "image/x-icon");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, faviconBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(faviconBytes);
            }
            log.trace("Sent favicon: bytes={}", faviconBytes.length);
        } catch (IOException e) {
            log.error("Could not read favicon.ico", e);
            sendText(exchange, 500, "Internal Server Error");
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
    void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.debug("Sent JSON response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Sends an HTML response with the specified HTTP status code.
     *
     * @param exchange HTTP exchange.
     * @param code     HTTP status code.
     * @param html     HTML payload.
     * @throws IOException If an I/O error occurs.
     */
    void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
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
    void sendText(HttpExchange exchange, int code, String text) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.debug("Sent text response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Sends a response with the specified HTTP status code, content type, and payload.
     *
     * @param exchange    HTTP exchange.
     * @param code        HTTP status code.
     * @param contentType Content-Type header value.
     * @param response    Response payload.
     * @throws IOException If an I/O error occurs.
     */
    protected void sendResponse(HttpExchange exchange, int code, String contentType, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.trace("Sent response: status={}, contentType={}, bytes={}", code, contentType, bytes.length);
    }

    /**
     * Sends an error HTTP response.
     *
     * @param exchange HTTP exchange.
     * @param code     HTTP error code.
     * @param message  Error message.
     * @throws IOException If an I/O error occurs.
     */
    protected void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        log.debug("Sent error response: status={}, bytes={}", code, bytes.length);
    }

    /**
     * Reads a resource file from the classpath into a string.
     *
     * @param path The path to the resource file.
     * @return The content of the file as a string.
     * @throws IOException If the resource is not found or cannot be read.
     */
    String readResourceFile(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}

