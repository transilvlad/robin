package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Interface for API endpoint handlers.
 *
 * <p>Provides a consistent contract for HTTP request handling across all API endpoints,
 * enabling self-registration and future plugin extensibility.
 *
 * <p>Implementations handle specific API paths (e.g., /client/send, /store, /users)
 * and are registered with the {@link ApiEndpoint} server during startup.
 */
public interface ApiHandler {

    /**
     * Handles an HTTP request.
     *
     * <p>Implementations should handle authentication, method validation,
     * request parsing, business logic, and response generation.
     *
     * @param exchange The HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs while processing the request.
     */
    void handle(HttpExchange exchange) throws IOException;

    /**
     * Returns the URL path this handler is registered for.
     *
     * <p>The path should start with a forward slash (e.g., "/client/send").
     * The handler will receive all requests matching this path prefix.
     *
     * @return The URL path for this handler.
     */
    String getPath();
}

