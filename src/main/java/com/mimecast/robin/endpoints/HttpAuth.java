package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP Authentication utility for endpoint security.
 *
 * <p>This class provides methods to validate HTTP Basic and Bearer Authentication
 * credentials and send appropriate authentication challenge responses.
 *
 * <p>Also supports IP allow lists where specific IP addresses or CIDR blocks
 * can bypass authentication.
 *
 * <p>Usage:
 * <pre>{@code
 * HttpAuth auth = new HttpAuth(endpointConfig, "Endpoint Name");
 * if (!auth.isAuthenticated(exchange)) {
 *     auth.sendAuthRequired(exchange);
 *     return;
 * }
 * }</pre>
 */
public class HttpAuth {
    private static final Logger log = LogManager.getLogger(HttpAuth.class);

    private final String authType;
    private final String authValue;
    private final boolean authEnabled;
    private final List<String> allowList;
    private final String realm;

    /**
     * Constructs an HttpAuth instance with the specified endpoint configuration.
     *
     * @param config EndpointConfig containing authentication settings.
     * @param realm  The authentication realm name.
     */
    public HttpAuth(EndpointConfig config, String realm) {
        this.authType = config.getAuthType();
        this.authValue = config.getAuthValue();
        this.authEnabled = config.isAuthEnabled();
        this.allowList = config.getAllowList();
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
     * Checks if the request contains valid authentication credentials or is from an allowed IP.
     *
     * @param exchange The HTTP exchange object.
     * @return True if authentication is disabled, IP is allowed, or credentials are valid; false otherwise.
     */
    public boolean isAuthenticated(HttpExchange exchange) {
        // Check if IP is in allow list
        if (isIpAllowed(exchange)) {
            log.trace("Authentication bypassed for allowed IP: {}", exchange.getRemoteAddress());
            return true;
        }

        // If authentication is not enabled, allow access
        if (!authEnabled) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null) {
            log.debug("Authentication failed: missing Authorization header from {}",
                    exchange.getRemoteAddress());
            return false;
        }

        // Handle Basic authentication
        if ("basic".equalsIgnoreCase(authType)) {
            return validateBasicAuth(authHeader, exchange);
        }
        // Handle Bearer authentication
        else if ("bearer".equalsIgnoreCase(authType)) {
            return validateBearerAuth(authHeader, exchange);
        }

        log.debug("Authentication failed: unsupported auth type '{}' from {}",
                authType, exchange.getRemoteAddress());
        return false;
    }

    /**
     * Validates Basic authentication.
     *
     * @param authHeader Authorization header value.
     * @param exchange   HTTP exchange object.
     * @return True if credentials are valid, false otherwise.
     */
    private boolean validateBasicAuth(String authHeader, HttpExchange exchange) {
        if (!authHeader.startsWith("Basic ")) {
            log.debug("Authentication failed: expected Basic auth from {}", exchange.getRemoteAddress());
            return false;
        }

        try {
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials),
                    StandardCharsets.UTF_8);

            if (credentials.equals(authValue)) {
                log.trace("Basic authentication successful from {}", exchange.getRemoteAddress());
                return true;
            } else {
                log.debug("Authentication failed: invalid Basic credentials from {}", exchange.getRemoteAddress());
                return false;
            }
        } catch (Exception e) {
            log.debug("Authentication failed: error decoding Basic credentials from {}: {}",
                    exchange.getRemoteAddress(), e.getMessage());
            return false;
        }
    }

    /**
     * Validates Bearer token authentication.
     *
     * @param authHeader Authorization header value.
     * @param exchange   HTTP exchange object.
     * @return True if token is valid, false otherwise.
     */
    private boolean validateBearerAuth(String authHeader, HttpExchange exchange) {
        if (!authHeader.startsWith("Bearer ")) {
            log.debug("Authentication failed: expected Bearer token from {}", exchange.getRemoteAddress());
            return false;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.equals(authValue)) {
            log.trace("Bearer authentication successful from {}", exchange.getRemoteAddress());
            return true;
        } else {
            log.debug("Authentication failed: invalid Bearer token from {}", exchange.getRemoteAddress());
            return false;
        }
    }

    /**
     * Checks if the remote IP address is in the allow list.
     *
     * @param exchange HTTP exchange object.
     * @return True if IP is allowed, false otherwise.
     */
    private boolean isIpAllowed(HttpExchange exchange) {
        if (allowList == null || allowList.isEmpty()) {
            return false;
        }

        InetSocketAddress remoteAddress = exchange.getRemoteAddress();
        if (remoteAddress == null) {
            return false;
        }

        InetAddress remoteIp = remoteAddress.getAddress();
        if (remoteIp == null) {
            return false;
        }

        for (String allowed : allowList) {
            if (matchesIpOrCidr(remoteIp, allowed)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if an IP address matches a specific IP or CIDR block.
     *
     * @param ip      The IP address to check.
     * @param pattern The IP address or CIDR block pattern.
     * @return True if the IP matches, false otherwise.
     */
    private boolean matchesIpOrCidr(InetAddress ip, String pattern) {
        try {
            pattern = pattern.trim();

            // Check for CIDR notation
            if (pattern.contains("/")) {
                return matchesCidr(ip, pattern);
            }

            // Simple IP address match
            InetAddress patternIp = InetAddress.getByName(pattern);
            return ip.equals(patternIp);

        } catch (Exception e) {
            log.warn("Invalid IP or CIDR pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if an IP address is within a CIDR block.
     *
     * @param ip   The IP address to check.
     * @param cidr The CIDR block notation (e.g., "192.168.1.0/24").
     * @return True if the IP is in the CIDR block, false otherwise.
     */
    private boolean matchesCidr(InetAddress ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            InetAddress networkAddress = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] ipBytes = ip.getAddress();
            byte[] networkBytes = networkAddress.getAddress();

            // IPv4 and IPv6 must match
            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Check full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // Check remaining bits
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((ipBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Error parsing CIDR '{}': {}", cidr, e.getMessage());
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

        // Set appropriate WWW-Authenticate header based on auth type
        if ("bearer".equalsIgnoreCase(authType)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
        } else {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        }

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        String message = "Unauthorized";
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
