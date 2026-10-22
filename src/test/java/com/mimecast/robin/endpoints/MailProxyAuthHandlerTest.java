package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.DovecotConfig;
import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the nginx mail proxy authentication endpoint.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MailProxyAuthHandlerTest {

    private static final Map<String, String> PASSWORDS = Map.of(
            "tony@example.com", "stark",
            "pepper@example.com", "potts");
    private static final Map<String, String> ALIASES = Map.of(
            "tony-alias@example.com", "tony@example.com",
            "tony@example.net", "tony@example.com",
            "ghost@example.com", "missing@example.com",
            "forward@example.com", "someone@example.org");

    private final AtomicReference<DovecotConfig.ProxyAuth> config = new AtomicReference<>();
    private final AtomicBoolean directoryAvailable = new AtomicBoolean(true);
    private final AtomicBoolean directoryBroken = new AtomicBoolean(false);
    private HttpServer server;
    private HttpClient client;
    private String url;

    @BeforeAll
    void setUp() throws IOException {
        Map<String, Object> endpointMap = new HashMap<>();
        endpointMap.put("authType", "none");
        HttpAuth auth = new HttpAuth(new EndpointConfig(endpointMap), "API");

        MailProxyAuthHandler.Directory directory = new MailProxyAuthHandler.Directory() {
            @Override
            public boolean userExists(String email) {
                if (directoryBroken.get()) {
                    throw new IllegalStateException("SQL user lookup unavailable");
                }
                return PASSWORDS.containsKey(email);
            }

            @Override
            public Optional<String> resolveAlias(String email) {
                return Optional.ofNullable(ALIASES.get(email));
            }

            @Override
            public boolean authenticate(String email, String password) {
                return password.equals(PASSWORDS.get(email));
            }
        };

        MailProxyAuthHandler handler = new MailProxyAuthHandler(new ApiEndpoint(), auth,
                config::get, () -> directoryAvailable.get() ? directory : null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(handler.getPath(), handler::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + handler.getPath();
        client = HttpClient.newHttpClient();
        enable(true);
    }

    @AfterAll
    void tearDown() {
        server.stop(0);
    }

    private void enable(boolean enabled) {
        config.set(new DovecotConfig.ProxyAuth(Map.of(
                "enabled", enabled,
                "imap", "127.0.0.1:1143",
                "failureWait", 1L)));
        directoryAvailable.set(true);
        directoryBroken.set(false);
    }

    private HttpResponse<String> login(String user, String pass, String protocol) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Auth-Method", "plain")
                .header("Auth-User", user)
                .header("Auth-Pass", pass)
                .header("Auth-Protocol", protocol)
                .header("Client-IP", "192.0.2.10")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    @Test
    void canonicalUserLogsIn() throws Exception {
        enable(true);
        HttpResponse<String> response = login("tony@example.com", "stark", "imap");
        assertEquals(200, response.statusCode());
        assertEquals("OK", header(response, "Auth-Status"));
        assertEquals("127.0.0.1", header(response, "Auth-Server"));
        assertEquals("1143", header(response, "Auth-Port"));
        assertEquals("tony@example.com", header(response, "Auth-User"));
    }

    @Test
    void aliasAndAutoAliasLogInAsCanonicalUser() throws Exception {
        enable(true);
        HttpResponse<String> alias = login("Tony-Alias%40example.com", "stark", "imap");
        assertEquals("OK", header(alias, "Auth-Status"));
        assertEquals("tony@example.com", header(alias, "Auth-User"));

        HttpResponse<String> autoAlias = login("tony@example.net", "stark", "imap");
        assertEquals("OK", header(autoAlias, "Auth-Status"));
        assertEquals("tony@example.com", header(autoAlias, "Auth-User"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        enable(true);
        HttpResponse<String> response = login("tony-alias@example.com", "potts", "imap");
        assertEquals("Invalid login or password", header(response, "Auth-Status"));
        assertEquals("1", header(response, "Auth-Wait"));
        assertTrue(header(response, "Auth-Server").isEmpty());
    }

    @Test
    void aliasToMissingOrExternalUserIsRejected() throws Exception {
        enable(true);
        assertEquals("Invalid login or password", header(login("ghost@example.com", "stark", "imap"), "Auth-Status"));
        assertEquals("Invalid login or password", header(login("forward@example.com", "stark", "imap"), "Auth-Status"));
        assertEquals("Invalid login or password", header(login("nobody@example.com", "stark", "imap"), "Auth-Status"));
    }

    @Test
    void unconfiguredProtocolIsTemporaryFailure() throws Exception {
        enable(true);
        HttpResponse<String> response = login("tony@example.com", "stark", "pop3");
        assertEquals("Temporary server problem, try again later", header(response, "Auth-Status"));
    }

    @Test
    void lookupFailureIsTemporaryFailure() throws Exception {
        enable(true);
        directoryBroken.set(true);
        HttpResponse<String> response = login("tony@example.com", "stark", "imap");
        assertEquals("Temporary server problem, try again later", header(response, "Auth-Status"));

        enable(true);
        directoryAvailable.set(false);
        response = login("tony@example.com", "stark", "imap");
        assertEquals("Temporary server problem, try again later", header(response, "Auth-Status"));
    }

    @Test
    void disabledEndpointIsNotFound() throws Exception {
        enable(false);
        HttpResponse<String> response = login("tony@example.com", "stark", "imap");
        assertEquals(404, response.statusCode());
        assertFalse(response.headers().firstValue("Auth-Status").isPresent());
        enable(true);
    }

    @Test
    void percentDecodingKeepsPlusSigns() {
        assertEquals("a+b@example.com", MailProxyAuthHandler.percentDecode("a+b%40example.com"));
        assertEquals("p%ss word", MailProxyAuthHandler.percentDecode("p%25ss%20word"));
        assertEquals("100%", MailProxyAuthHandler.percentDecode("100%"));
    }
}
