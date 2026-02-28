package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.UserConfig;
import com.mimecast.robin.db.SharedDataSource;
import com.mimecast.robin.main.Config;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for the /users endpoint.
 *
 * <p>Provides user management API operations:
 * <ul>
 *   <li><b>GET /users</b> — Lists all configured users.</li>
 *   <li><b>GET /users/{username}/exists</b> — Checks if a user exists.</li>
 *   <li><b>POST /users/authenticate</b> — Validates user credentials.</li>
 * </ul>
 *
 * <p>Supports two backends:
 * <ul>
 *   <li><b>SQL:</b> Database-backed user storage via Dovecot auth SQL.</li>
 *   <li><b>Config:</b> File-based user storage via users config.</li>
 * </ul>
 */
public class UsersHandler implements ApiHandler {
    private static final Logger log = LogManager.getLogger(UsersHandler.class);
    private static final String PATH = "/users";

    private final HttpEndpoint endpoint;
    private final HttpAuth auth;
    private final Gson gson = ApiEndpointUtils.getGson();

    /**
     * Backend types for user operations.
     */
    private enum UsersBackend {
        /** Configuration file-based user storage. */
        CONFIG,
        /** SQL database-based user storage. */
        SQL,
        /** No backend configured. */
        NONE
    }

    /**
     * Constructs a new UsersHandler.
     *
     * @param endpoint The parent HTTP endpoint for response utilities.
     * @param auth     The authentication handler.
     */
    public UsersHandler(HttpEndpoint endpoint, HttpAuth auth) {
        this.endpoint = endpoint;
        this.auth = auth;
    }

    @Override
    public String getPath() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String tail = path.length() > PATH.length() ? path.substring(PATH.length()) : "";

        if (tail.isEmpty() || "/".equals(tail)) {
            if (!"GET".equalsIgnoreCase(method)) {
                endpoint.sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            handleUsersList(exchange);
            return;
        }

        if ("/authenticate".equals(tail)) {
            if (!"POST".equalsIgnoreCase(method)) {
                endpoint.sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            handleUsersAuthenticate(exchange);
            return;
        }

        if (tail.endsWith("/exists")) {
            if (!"GET".equalsIgnoreCase(method)) {
                endpoint.sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String username = tail.substring(1, tail.length() - "/exists".length());
            username = URLDecoder.decode(username, StandardCharsets.UTF_8);
            if (username.isBlank()) {
                endpoint.sendJson(exchange, 400, "{\"error\":\"Missing username\"}");
                return;
            }
            handleUsersExists(exchange, username);
            return;
        }

        endpoint.sendText(exchange, 404, "Not Found");
    }

    /**
     * Handles GET /users request to list all configured users.
     *
     * @param exchange HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs.
     */
    private void handleUsersList(HttpExchange exchange) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            endpoint.sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        try {
            List<String> users = backend == UsersBackend.SQL ? getSqlUsers() : getConfigUsers();
            users.sort(String::compareToIgnoreCase);
            Map<String, Object> response = new HashMap<>();
            response.put("source", backend.name().toLowerCase());
            response.put("count", users.size());
            response.put("users", users);
            endpoint.sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users list: {}", e.getMessage());
            endpoint.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    /**
     * Handles GET /users/{username}/exists request to check if a user exists.
     *
     * @param exchange HTTP exchange containing request and response.
     * @param username Username to check.
     * @throws IOException If an I/O error occurs.
     */
    private void handleUsersExists(HttpExchange exchange, String username) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            endpoint.sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        try {
            boolean exists = backend == UsersBackend.SQL ? sqlUserExists(username) : configUserExists(username);
            Map<String, Object> response = new HashMap<>();
            response.put("source", backend.name().toLowerCase());
            response.put("username", username);
            response.put("exists", exists);
            endpoint.sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users/{}/exists: {}", username, e.getMessage());
            endpoint.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    /**
     * Handles POST /users/authenticate request to validate user credentials.
     *
     * @param exchange HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs.
     */
    private void handleUsersAuthenticate(HttpExchange exchange) throws IOException {
        UsersBackend backend = getUsersBackend();
        if (backend == UsersBackend.NONE) {
            endpoint.sendJson(exchange, 503, gson.toJson(buildUsersBackendNotConfiguredError()));
            return;
        }

        String body = ApiEndpointUtils.readBody(exchange.getRequestBody());
        if (body.isBlank()) {
            endpoint.sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        Map<String, Object> map;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Gson().fromJson(body, Map.class);
            map = parsed;
        } catch (Exception e) {
            endpoint.sendJson(exchange, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }
        String username = map != null && map.get("username") != null ? String.valueOf(map.get("username")).trim() : "";
        String password = map != null && map.get("password") != null ? String.valueOf(map.get("password")) : "";
        if (username.isBlank() || password.isBlank()) {
            endpoint.sendJson(exchange, 400, "{\"error\":\"Missing username or password\"}");
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
            endpoint.sendJson(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            log.error("Error processing /users/authenticate for {}: {}", username, e.getMessage());
            endpoint.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    /**
     * Determines the active users backend based on configuration.
     *
     * @return The active UsersBackend type.
     */
    private UsersBackend getUsersBackend() {
        if (Config.getServer().getDovecot().isAuthSqlEnabled()) {
            return UsersBackend.SQL;
        }
        if (Config.getServer().getUsers().isListEnabled()
                || !Config.getServer().getUsers().getList().isEmpty()) {
            return UsersBackend.CONFIG;
        }
        return UsersBackend.NONE;
    }

    /**
     * Builds an error response map for when no users backend is configured.
     *
     * @return Map containing error details and configuration status.
     */
    private Map<String, Object> buildUsersBackendNotConfiguredError() {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Users API backend is not configured");
        error.put("authSqlEnabled", Config.getServer().getDovecot().isAuthSqlEnabled());
        error.put("usersListEnabled", Config.getServer().getUsers().isListEnabled());
        error.put("usersListCount", Config.getServer().getUsers().getList().size());
        return error;
    }

    /**
     * Gets all usernames from the configuration file backend.
     *
     * @return List of configured usernames.
     */
    private List<String> getConfigUsers() {
        List<String> users = new ArrayList<>();
        for (UserConfig user : Config.getServer().getUsers().getList()) {
            if (user.getName() != null && !user.getName().isBlank()) {
                users.add(user.getName());
            }
        }
        return users;
    }

    /**
     * Checks if a user exists in the configuration file backend.
     *
     * @param username Username to check.
     * @return {@code true} if the user exists.
     */
    private boolean configUserExists(String username) {
        return Config.getServer().getUsers().getUser(username).isPresent();
    }

    /**
     * Authenticates a user against the configuration file backend.
     *
     * @param username Username to authenticate.
     * @param password Password to validate.
     * @return {@code true} if credentials are valid.
     */
    private boolean configAuthenticate(String username, String password) {
        return Config.getServer().getUsers().getUser(username)
                .map(u -> password.equals(u.getPass()))
                .orElse(false);
    }

    /**
     * Gets all usernames from the SQL database backend.
     *
     * @return List of usernames from the database.
     * @throws Exception If a database error occurs.
     */
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

    /**
     * Checks if a user exists in the SQL database backend.
     *
     * @param username Username to check.
     * @return {@code true} if the user exists in the database.
     * @throws Exception If a database error occurs.
     */
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

    /**
     * Authenticates a user against the SQL database backend.
     *
     * @param username Username to authenticate.
     * @param password Password to validate.
     * @return {@code true} if credentials are valid.
     * @throws Exception If a database error occurs.
     */
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
}

