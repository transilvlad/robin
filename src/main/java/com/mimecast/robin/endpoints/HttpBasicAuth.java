package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP Basic Authentication utility for endpoint security.
 *
 * <p>This class provides methods to validate HTTP Basic Authentication credentials
 * and send appropriate authentication challenge responses.
 *
 * <p>Usage:
 * <pre>{@code
 * HttpBasicAuth auth = new HttpBasicAuth(username, password);
 * if (!auth.isAuthenticated(exchange)) {
 *     auth.sendAuthRequired(exchange);
 *     return;
 * }
 * }</pre>
 */
public class HttpBasicAuth {
    private static final Logger log = LogManager.getLogger(HttpBasicAuth.class);

    private final String username;
    private final String password;
    private final boolean authEnabled;
    private final String realm;

    /**
     * Constructs an HttpBasicAuth instance with the specified credentials and realm.
     *
     * @param username The username for HTTP Basic Authentication (null or empty to disable authentication).
     * @param password The password for HTTP Basic Authentication.
     * @param realm    The authentication realm name.
     */
    public HttpBasicAuth(String username, String password, String realm) {
        this.username = username;
        this.password = password;
        this.authEnabled = (username != null && !username.isEmpty() && password != null && !password.isEmpty());
        this.realm = realm != null ? realm : "Restricted";
    }

    /**
     * Checks if authentication is enabled.
     *
     * @return True if authentication is enabled, false otherwise.
     */
    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /**
     * Checks if the request contains valid authentication credentials.
     *
     * @param exchange The HTTP exchange object.
     * @return True if authentication is disabled or credentials are valid, false otherwise.
     */
    public boolean isAuthenticated(HttpExchange exchange) {
        if (!authEnabled) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            log.debug("Authentication failed: missing or invalid Authorization header from {}",
                    exchange.getRemoteAddress());
            return false;
        }

        try {
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);

            if (parts.length == 2 && parts[0].equals(username) && parts[1].equals(password)) {
                log.trace("Authentication successful for user '{}' from {}", parts[0], exchange.getRemoteAddress());
                return true;
            } else {
                log.debug("Authentication failed: invalid credentials from {}", exchange.getRemoteAddress());
                return false;
            }
        } catch (Exception e) {
            log.debug("Authentication failed: error decoding credentials from {}: {}",
                    exchange.getRemoteAddress(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends an HTTP 401 Unauthorized response with WWW-Authenticate header.
     *
     * @param exchange The HTTP exchange object.
     * @throws IOException If an I/O error occurs.
     */
    public void sendAuthRequired(HttpExchange exchange) throws IOException {
        log.debug("Sending 401 Unauthorized to {}", exchange.getRemoteAddress());
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        String message = "Unauthorized";
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
