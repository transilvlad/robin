package com.mimecast.robin.endpoints;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.config.DovecotConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.SqlAuthProvider;
import com.mimecast.robin.sasl.SqlUserLookup;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Handler for the /mail/proxy-auth endpoint.
 *
 * <p>Implements the nginx mail proxy {@code auth_http} protocol so an nginx IMAP/POP3 proxy can sit in
 * front of a mailbox server that does not resolve aliases itself. The login is resolved the same way
 * the bundled Dovecot passdb does it: an exact user match first, then an alias or auto alias that points
 * at an existing user. The password is verified against the canonical user and nginx is told to log in
 * to the configured backend as that canonical user.
 *
 * <p>Request headers (sent by nginx): {@code Auth-Method}, {@code Auth-User}, {@code Auth-Pass},
 * {@code Auth-Protocol}, {@code Client-IP}. Response headers: {@code Auth-Status}, and on success
 * {@code Auth-Server}, {@code Auth-Port} and {@code Auth-User}; on failure {@code Auth-Wait}.
 *
 * <p>The endpoint is disabled unless {@code proxyAuth.enabled} is set in {@code dovecot.json5}
 * and requires the SQL authentication backend.
 */
public class MailProxyAuthHandler implements ApiHandler {
    private static final Logger log = LogManager.getLogger(MailProxyAuthHandler.class);
    private static final String PATH = "/mail/proxy-auth";
    private static final String INVALID = "Invalid login or password";
    private static final String TEMPORARY = "Temporary server problem, try again later";

    private final HttpEndpoint endpoint;
    private final HttpAuth auth;
    private final Supplier<DovecotConfig.ProxyAuth> configSupplier;
    private final Supplier<Directory> directorySupplier;

    /**
     * User directory used to resolve logins and verify passwords.
     */
    public interface Directory {
        /**
         * Checks whether a mailbox user exists.
         *
         * @param email Email address.
         * @return True if the user exists.
         */
        boolean userExists(String email);

        /**
         * Resolves an address through aliases and auto aliases.
         *
         * @param email Email address.
         * @return Destination address, if any.
         */
        Optional<String> resolveAlias(String email);

        /**
         * Verifies a password for a canonical user.
         *
         * @param email    Canonical user address.
         * @param password Plain text password.
         * @return True if the password matches.
         */
        boolean authenticate(String email, String password);
    }

    /**
     * Constructs a new MailProxyAuthHandler backed by the shared SQL authentication manager.
     *
     * @param endpoint The parent HTTP endpoint for response utilities.
     * @param auth     The authentication handler.
     */
    public MailProxyAuthHandler(HttpEndpoint endpoint, HttpAuth auth) {
        this(endpoint, auth,
                () -> Config.getServer().getDovecot().getProxyAuth(),
                MailProxyAuthHandler::sqlDirectory);
    }

    /**
     * Constructs a new MailProxyAuthHandler with explicit configuration and directory suppliers.
     *
     * @param endpoint          The parent HTTP endpoint for response utilities.
     * @param auth              The authentication handler.
     * @param configSupplier    Supplier of the proxy authentication configuration.
     * @param directorySupplier Supplier of the user directory (may return null when unavailable).
     */
    public MailProxyAuthHandler(HttpEndpoint endpoint, HttpAuth auth,
                                Supplier<DovecotConfig.ProxyAuth> configSupplier,
                                Supplier<Directory> directorySupplier) {
        this.endpoint = endpoint;
        this.auth = auth;
        this.configSupplier = configSupplier;
        this.directorySupplier = directorySupplier;
    }

    @Override
    public String getPath() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        DovecotConfig.ProxyAuth config = configSupplier.get();
        if (!config.isEnabled()) {
            endpoint.sendText(exchange, 404, "Not Found");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            endpoint.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String protocol = header(exchange, "Auth-Protocol").toLowerCase(Locale.ROOT);
        String method = header(exchange, "Auth-Method").toLowerCase(Locale.ROOT);
        String login = percentDecode(header(exchange, "Auth-User")).trim().toLowerCase(Locale.ROOT);
        String password = percentDecode(header(exchange, "Auth-Pass"));
        String clientIp = header(exchange, "Client-IP");

        String backend = switch (protocol) {
            case "imap" -> config.getImap();
            case "pop3" -> config.getPop3();
            default -> "";
        };
        if (backend.isBlank()) {
            log.warn("Proxy auth rejected: protocol '{}' has no backend configured", protocol);
            fail(exchange, TEMPORARY, config);
            return;
        }

        if (!"plain".equals(method) && !"login".equals(method)) {
            log.info("Proxy auth rejected: unsupported method '{}' for {} from {}", method, login, clientIp);
            fail(exchange, INVALID, config);
            return;
        }

        if (login.isEmpty() || password.isEmpty()) {
            fail(exchange, INVALID, config);
            return;
        }

        Directory directory = directorySupplier.get();
        if (directory == null) {
            log.error("Proxy auth unavailable: SQL authentication backend is not initialized");
            fail(exchange, TEMPORARY, config);
            return;
        }

        String canonical;
        try {
            Optional<String> resolved = resolve(directory, login);
            if (resolved.isEmpty() || !directory.authenticate(resolved.get(), password)) {
                log.info("Proxy auth failed for {} ({}) from {}", login, protocol, clientIp);
                fail(exchange, INVALID, config);
                return;
            }
            canonical = resolved.get();
        } catch (RuntimeException e) {
            log.error("Proxy auth lookup failed for {}: {}", login, e.getMessage());
            fail(exchange, TEMPORARY, config);
            return;
        }

        int separator = backend.lastIndexOf(':');
        String host = separator > 0 ? backend.substring(0, separator) : backend;
        String port = separator > 0 ? backend.substring(separator + 1) : ("pop3".equals(protocol) ? "110" : "143");
        String address;
        try {
            address = InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            log.error("Proxy auth backend '{}' cannot be resolved: {}", host, e.getMessage());
            fail(exchange, TEMPORARY, config);
            return;
        }

        log.info("Proxy auth OK for {} as {} ({}) from {}", login, canonical, protocol, clientIp);
        exchange.getResponseHeaders().set("Auth-Status", "OK");
        exchange.getResponseHeaders().set("Auth-Server", address);
        exchange.getResponseHeaders().set("Auth-Port", port);
        exchange.getResponseHeaders().set("Auth-User", canonical);
        endpoint.sendText(exchange, 200, "OK");
    }

    /**
     * Resolves a login to a canonical mailbox user.
     * <p>
     * Exact user match wins; otherwise the alias or auto alias destination is used if it is an existing user.
     *
     * @param directory User directory.
     * @param login     Lower-cased login.
     * @return Canonical user address, or empty when the login does not map to a mailbox user.
     */
    static Optional<String> resolve(Directory directory, String login) {
        if (directory.userExists(login)) {
            return Optional.of(login);
        }
        Optional<String> destination = directory.resolveAlias(login);
        if (destination.isPresent()) {
            String target = destination.get().trim().toLowerCase(Locale.ROOT);
            if (!target.isEmpty() && !target.equals(login) && directory.userExists(target)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    /**
     * Decodes nginx percent escaping (a plus sign is kept literally).
     *
     * @param value Escaped value.
     * @return Decoded value.
     */
    static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value == null ? "" : value;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private void fail(HttpExchange exchange, String status, DovecotConfig.ProxyAuth config) throws IOException {
        exchange.getResponseHeaders().set("Auth-Status", status);
        exchange.getResponseHeaders().set("Auth-Wait", String.valueOf(config.getFailureWait()));
        endpoint.sendText(exchange, 200, status);
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private static Directory sqlDirectory() {
        SqlUserLookup lookup = SqlAuthManager.getUserLookup();
        SqlAuthProvider provider = SqlAuthManager.getAuthProvider();
        if (lookup == null || provider == null || !Config.getServer().getDovecot().isAuthSqlEnabled()) {
            return null;
        }
        return new Directory() {
            @Override
            public boolean userExists(String email) {
                return lookup.lookup(email).isPresent();
            }

            @Override
            public Optional<String> resolveAlias(String email) {
                return lookup.resolveAlias(email);
            }

            @Override
            public boolean authenticate(String email, String password) {
                return provider.authenticate(email, password);
            }
        };
    }
}
