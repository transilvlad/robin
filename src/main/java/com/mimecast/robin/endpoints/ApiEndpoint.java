package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * API endpoint for case submission and queue management.
 *
 * <p>Starts a lightweight HTTP server to accept JSON/JSON5 case definitions
 * (either as raw JSON in the body or by providing a file path) and executes the client.
 *
 * <p>Endpoints are handled by dedicated handler classes:
 * <ul>
 *   <li><b>GET /</b> — Landing page ({@link ApiEndpoint})</li>
 *   <li><b>POST /client/send</b> — Send messages ({@link ClientSendHandler})</li>
 *   <li><b>POST /client/queue</b> — Queue messages ({@link ClientQueueHandler})</li>
 *   <li><b>GET /client/queue/list</b> — List queue ({@link QueueOperationsHandler})</li>
 *   <li><b>POST /client/queue/delete|retry|bounce</b> — Queue operations ({@link QueueOperationsHandler})</li>
 *   <li><b>GET /logs</b> — Search logs ({@link LogsHandler})</li>
 *   <li><b>GET|POST|PATCH|DELETE /users/...</b> — User management ({@link UsersHandler})</li>
 *   <li><b>GET|POST|PATCH|DELETE /store/...</b> — Storage browser ({@link StoreHandler})</li>
 *   <li><b>GET /health</b> — Health check ({@link ApiEndpoint})</li>
 * </ul>
 */
public class ApiEndpoint extends HttpEndpoint {
    private static final Logger log = LogManager.getLogger(ApiEndpoint.class);

    /**
     * Starts the API endpoint with endpoint configuration.
     *
     * @param config EndpointConfig containing port and authentication settings (authType, authValue, allowList).
     * @throws IOException If an I/O error occurs during server startup.
     */
    @Override
    public void start(EndpointConfig config) throws IOException {
        // Initialize authentication handler.
        this.auth = new HttpAuth(config, "API");

        // Get storage path override from config.
        String configuredStoragePath = config.getStringProperty("storagePath");
        String storagePathOverride = (configuredStoragePath == null || configuredStoragePath.isBlank())
                ? null : configuredStoragePath;

        // Create handlers.
        ClientSendHandler clientSendHandler = new ClientSendHandler(this, auth);
        ClientQueueHandler clientQueueHandler = new ClientQueueHandler(this, auth);
        QueueOperationsHandler queueHandler = new QueueOperationsHandler(this, auth);
        UsersHandler usersHandler = new UsersHandler(this, auth);
        StoreHandler storeHandler = new StoreHandler(this, auth, storagePathOverride);

        // Bind the HTTP server to the configured API port.
        int apiPort = config.getPort(8090);
        HttpServer server = HttpServer.create(new InetSocketAddress(apiPort), 10);

        // Register endpoints.

        // Landing page for API endpoint discovery.
        server.createContext("/", this::handleLandingPage);

        // Favicon.
        server.createContext("/favicon.ico", this::handleFavicon);

        // Client send endpoint.
        server.createContext("/client/send", clientSendHandler::handle);

        // Client queue submission endpoint.
        server.createContext("/client/queue", clientQueueHandler::handle);

        // Queue listing endpoint.
        server.createContext("/client/queue/list", queueHandler::handleList);

        // Queue control endpoints.
        server.createContext("/client/queue/delete", queueHandler::handleDelete);
        server.createContext("/client/queue/retry", queueHandler::handleRetry);
        server.createContext("/client/queue/bounce", queueHandler::handleBounce);

        // Logs search endpoint.
        server.createContext("/logs", this::handleLogs);

        // User integration endpoints.
        server.createContext("/users", usersHandler::handle);

        // Storage browser endpoint.
        server.createContext("/store", storeHandler::handle);

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
     *
     * @param exchange HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs while serving the page.
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
     * Handles <b>GET /logs</b> requests.
     *
     * <p>Searches current and previous log4j2 log files for lines matching a query string.
     *
     * @param exchange HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs while processing the request.
     */
    private void handleLogs(HttpExchange exchange) throws IOException {
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
            var queryParams = ApiEndpointUtils.parseQuery(exchange.getRequestURI());
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
}

