package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BotEndpointCallerTest {

    @Test
    void testPostJsonWithBasicAuthAndHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();

            Session session = new Session();
            session.putMagic("botSecret", "user:pass");
            session.putMagic("tenant", "acme");
            session.addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
            Connection connection = new Connection(session);

            Map<String, Object> map = new HashMap<>();
            map.put("endpoint", server.url("/dmarc").toString());
            map.put("authType", "basic");
            map.put("authValue", "{$botSecret}");
            map.put("headers", Map.of("X-Tenant", "{$tenant}"));
            BotConfig.BotDefinition botDefinition = new BotConfig.BotDefinition(map);

            BotEndpointCaller.postJson("{\"ok\":true}", connection, botDefinition, "dmarc", LogManager.getLogger(getClass()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"));
            String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedAuth, request.getHeader("Authorization"));
            assertEquals("acme", request.getHeader("X-Tenant"));
        }
    }

    @Test
    void testPostJsonWithBearerAuth() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();

            Session session = new Session();
            session.addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
            Connection connection = new Connection(session);

            Map<String, Object> map = new HashMap<>();
            map.put("endpoint", server.url("/tlsrpt").toString());
            map.put("authType", "bearer");
            map.put("authValue", "token123");
            map.put("headers", Map.of());
            BotConfig.BotDefinition botDefinition = new BotConfig.BotDefinition(map);

            BotEndpointCaller.postJson("{\"ok\":true}", connection, botDefinition, "tlsrpt", LogManager.getLogger(getClass()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("Bearer token123", request.getHeader("Authorization"));
        }
    }

    @Test
    void testPostJsonWithNoAuth() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();

            Session session = new Session();
            session.addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
            Connection connection = new Connection(session);

            Map<String, Object> map = new HashMap<>();
            map.put("endpoint", server.url("/forensic").toString());
            map.put("authType", "none");
            map.put("authValue", "");
            map.put("headers", Map.of("X-Test", "v"));
            BotConfig.BotDefinition botDefinition = new BotConfig.BotDefinition(map);

            BotEndpointCaller.postJson("{\"ok\":true}", connection, botDefinition, "forensic", LogManager.getLogger(getClass()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertNull(request.getHeader("Authorization"));
            assertEquals("v", request.getHeader("X-Test"));
        }
    }
}
