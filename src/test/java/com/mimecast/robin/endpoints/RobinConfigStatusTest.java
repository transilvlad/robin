package com.mimecast.robin.endpoints;

import com.google.gson.JsonParser;
import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.config.store.ConfigStoreSyncManager;
import com.mimecast.robin.main.Config;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class RobinConfigStatusTest {
    @TempDir
    Path directory;
    private RobinServiceEndpoint endpoint;
    private URI base;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String authorization = "Basic " + Base64.getEncoder()
            .encodeToString("fixture:synthetic".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void start() throws Exception {
        ConfigStoreSyncManager.setConfigDir(directory.toString());
        Files.writeString(directory.resolve("properties.json5"), "{}");
        Files.writeString(directory.resolve("server.json5"), "{hostname:'fixture.test',password:'not-exported'}");
        Config.initProperties(directory.resolve("properties.json5").toString());
        Config.initServer(directory.resolve("server.json5").toString());
        endpoint = new RobinServiceEndpoint();
        endpoint.auth = new HttpAuth(new EndpointConfig(Map.of("authType", "basic", "authValue", "fixture:synthetic")), "test");
        endpoint.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContexts();
        endpoint.server.start();
        base = URI.create("http://127.0.0.1:" + endpoint.server.getAddress().getPort());
    }

    @AfterEach
    void stop() throws Exception {
        endpoint.stop();
        ConfigLoader.load("src/test/resources/cfg/");
    }

    @Test
    void runtimeProofRequiresCredentialsAndDoesNotExportValues() throws Exception {
        var denied = client.send(HttpRequest.newBuilder(base.resolve("/config/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, denied.statusCode());

        var response = send("/config/status", false);
        assertEquals(200, response.statusCode());
        var body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("configuration-snapshot", body.get("scope").getAsString());
        assertFalse(body.get("restartRequired").getAsBoolean());
        assertTrue(body.getAsJsonObject("sourceFiles").has("server.json5"));
        assertFalse(response.body().contains("not-exported"));
        assertFalse(response.body().contains("fixture.test"));
    }

    @Test
    void reloadResponseDistinguishesSnapshotApplyFromFailureAndRestart() throws Exception {
        var accepted = send("/config/reload", true);
        var result = JsonParser.parseString(accepted.body()).getAsJsonObject();
        assertEquals(200, accepted.statusCode());
        assertTrue(result.get("applied").getAsBoolean());
        assertTrue(result.get("restartRequired").getAsBoolean());

        Files.writeString(directory.resolve("server.json5"), "{password:'sensitive',broken:");
        var failed = send("/config/reload", true);
        assertEquals(500, failed.statusCode());
        assertFalse(JsonParser.parseString(failed.body()).getAsJsonObject().get("applied").getAsBoolean());
        assertFalse(failed.body().contains("sensitive"));
        assertEquals("fixture.test", Config.getServer().getHostname());
    }

    private HttpResponse<String> send(String path, boolean post) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).header("Authorization", authorization);
        if (post) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
