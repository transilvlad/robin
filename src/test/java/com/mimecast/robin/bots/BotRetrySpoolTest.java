package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.config.server.BotRetryConfig;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

// BotRetrySpool.instance is static and read by BotEndpointCaller.postJson.
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("bot-retry-spool")
class BotRetrySpoolTest {

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @AfterEach
    void clearInstance() {
        BotRetrySpool.setInstance(null);
    }

    private BotRetrySpool spool(Map<String, Object> settings) throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("jitterFraction", 0.0);
        map.putAll(settings);
        return new BotRetrySpool(new BotRetryConfig(map), tempDir, clock::get, () -> {
        });
    }

    private List<Path> files(String folder) throws IOException {
        try (Stream<Path> stream = Files.list(tempDir.resolve(folder))) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private BotRetryEntry entry(Path file) throws IOException {
        return new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), BotRetryEntry.class);
    }

    private void advancePast(Path file) throws IOException {
        clock.set(entry(file).getNextAttemptAt());
    }

    @Test
    void testRetryDeliversStoredRequestWhenEndpointRecovers() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            BotRetrySpool spool = spool(Map.of());

            assertTrue(spool.enqueue("dmarc", server.url("/dmarc").toString(), false,
                    Map.of("Authorization", "Bearer token123"), "{\"ok\":true}", "ConnectException: refused"));
            assertEquals(1, spool.getPending());
            Path file = files("retry").get(0);
            assertEquals(1_000_060L, entry(file).getNextAttemptAt());
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            }

            assertEquals(0, spool.processDue());
            assertEquals(0, server.getRequestCount());

            advancePast(file);
            assertEquals(1, spool.processDue());
            assertEquals(0, spool.getPending());
            assertTrue(files("retry").isEmpty());

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("Bearer token123", request.getHeader("Authorization"));
            assertEquals("{\"ok\":true}", request.getBody().readUtf8());
        }
    }

    @Test
    void testRetryableFailureIsRescheduledWithBackoff() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("restarting"));
            server.start();
            BotRetrySpool spool = spool(Map.of());
            spool.enqueue("tlsrpt", server.url("/tlsrpt").toString(), false, Map.of(), "{}", "first");
            Path file = files("retry").get(0);

            advancePast(file);
            long now = clock.get();
            assertEquals(0, spool.processDue());

            BotRetryEntry rescheduled = entry(file);
            assertEquals(2, rescheduled.getRetryCount());
            assertEquals(now + 120, rescheduled.getNextAttemptAt());
            assertTrue(rescheduled.getLastError().contains("503"));
            assertEquals(1, spool.getPending());
        }
    }

    @Test
    void testRejectedRequestMovesToDead() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400).setBody("bad report"));
            server.start();
            BotRetrySpool spool = spool(Map.of());
            spool.enqueue("forensic", server.url("/forensic").toString(), false, Map.of(), "{}", "first");
            advancePast(files("retry").get(0));

            spool.processDue();

            assertEquals(0, spool.getPending());
            assertEquals(1, spool.getDead());
            assertTrue(files("retry").isEmpty());
            assertTrue(entry(files("dead").get(0)).getLastError().contains("400"));
        }
    }

    @Test
    void testRequestMovesToDeadAfterTotalRetries() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.start();
            BotRetrySpool spool = spool(Map.of("totalRetries", 1L));
            spool.enqueue("dmarc", server.url("/dmarc").toString(), false, Map.of(), "{}", "first");
            advancePast(files("retry").get(0));

            spool.processDue();

            assertEquals(1, spool.getDead());
            assertTrue(files("retry").isEmpty());
        }
    }

    @Test
    void testRequestMovesToDeadAfterMaxAge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.start();
            BotRetrySpool spool = spool(Map.of("maxAgeSeconds", 60L));
            spool.enqueue("dmarc", server.url("/dmarc").toString(), false, Map.of(), "{}", "first");
            clock.addAndGet(61);

            spool.processDue();

            assertEquals(1, spool.getDead());
        }
    }

    @Test
    void testPendingLimitAndDiskCheckRefuseNewRequests() throws Exception {
        BotRetrySpool spool = spool(Map.of("maxPending", 1L));
        assertTrue(spool.enqueue("dmarc", "http://127.0.0.1:1/dmarc", false, Map.of(), "{}", "first"));
        assertFalse(spool.enqueue("dmarc", "http://127.0.0.1:1/dmarc", false, Map.of(), "{}", "second"));
        assertEquals(1, spool.getPending());

        BotRetrySpool noSpace = new BotRetrySpool(new BotRetryConfig(Map.of()), tempDir.resolve("full"), clock::get,
                () -> {
                    throw new IOException("Insufficient disk space");
                });
        assertFalse(noSpace.enqueue("dmarc", "http://127.0.0.1:1/dmarc", false, Map.of(), "{}", "first"));
    }

    @Test
    void testStoredRequestsSurviveRestart() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            spool(Map.of()).enqueue("dmarc", server.url("/dmarc").toString(), false, Map.of(), "{}", "first");
            Path file = files("retry").get(0);

            BotRetrySpool restarted = spool(Map.of());
            assertEquals(1, restarted.getPending());
            advancePast(file);
            assertEquals(1, restarted.processDue());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void testUnreadableFileMovesToDead() throws Exception {
        BotRetrySpool spool = spool(Map.of());
        Files.writeString(tempDir.resolve("retry").resolve("000000000001-broken.json"), "{not json");

        spool.processDue();

        assertTrue(files("retry").isEmpty());
        assertEquals(1, files("dead").size());
    }

    @Test
    void testFileNamesSortByFirstFailure() {
        BotRetryEntry older = new BotRetryEntry().setUid("b").setFirstFailedAt(99L);
        BotRetryEntry newer = new BotRetryEntry().setUid("a").setFirstFailedAt(1000L);
        assertTrue(BotRetrySpool.fileName(older).compareTo(BotRetrySpool.fileName(newer)) < 0);
    }

    @Test
    void testStatsJsonReportsPendingAndOldest() throws Exception {
        assertEquals("{\"enabled\":false}", BotRetrySpool.getStatsJson());

        BotRetrySpool spool = spool(Map.of());
        spool.enqueue("dmarc", "http://127.0.0.1:1/dmarc", false, Map.of(), "{}", "first");
        BotRetrySpool.setInstance(spool);

        assertEquals("{\"enabled\":true,\"pending\":1,\"dead\":0,\"oldestPendingEpochSeconds\":1000000}",
                BotRetrySpool.getStatsJson());
    }

    @Test
    void testPostJsonQueuesRetryableFailuresOnly() throws Exception {
        BotRetrySpool spool = spool(Map.of());
        BotRetrySpool.setInstance(spool);

        Session session = new Session();
        session.putMagic("tenant", "acme");
        session.addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
        Connection connection = new Connection(session);

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(422));
            server.start();
            Map<String, Object> map = new HashMap<>();
            map.put("endpoint", server.url("/dmarc").toString());
            map.put("headers", Map.of("X-Tenant", "{$tenant}"));
            BotConfig.BotDefinition definition = new BotConfig.BotDefinition(map);

            BotEndpointCaller.postJson("{\"a\":1}", connection, definition, "dmarc", LogManager.getLogger(getClass()));
            assertEquals(1, spool.getPending());
            assertEquals("acme", entry(files("retry").get(0)).getHeaders().get("X-Tenant"));

            BotEndpointCaller.postJson("{\"a\":2}", connection, definition, "dmarc", LogManager.getLogger(getClass()));
            assertEquals(1, spool.getPending());
        }

        // Connection refused.
        MockWebServer closed = new MockWebServer();
        closed.start();
        String endpoint = closed.url("/dmarc").toString();
        closed.close();
        BotEndpointCaller.postJson("{\"a\":3}", connection,
                new BotConfig.BotDefinition(new HashMap<>(Map.of("endpoint", endpoint))), "dmarc",
                LogManager.getLogger(getClass()));
        assertEquals(2, spool.getPending());
    }

    @Test
    void testPostJsonQueuesRequestsSkippedWhileCircuitIsOpen() throws Exception {
        BotRetrySpool spool = spool(Map.of());
        BotRetrySpool.setInstance(spool);

        Session session = new Session();
        session.addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
        Connection connection = new Connection(session);

        try (MockWebServer server = new MockWebServer()) {
            for (int i = 0; i < 5; i++) {
                server.enqueue(new MockResponse().setResponseCode(503));
            }
            server.start();
            Map<String, Object> map = new HashMap<>();
            map.put("endpoint", server.url("/unavailable").toString());
            BotConfig.BotDefinition definition = new BotConfig.BotDefinition(map);

            for (int i = 0; i < 6; i++) {
                BotEndpointCaller.postJson("{}", connection, definition, "dmarc", LogManager.getLogger(getClass()));
            }

            assertEquals(5, server.getRequestCount());
            assertEquals(6, spool.getPending());

            // The circuit is still open, so due requests wait without using a retry.
            for (Path file : files("retry")) {
                clock.set(Math.max(clock.get(), entry(file).getNextAttemptAt()));
            }
            assertEquals(0, spool.processDue());
            assertEquals(5, server.getRequestCount());
            assertEquals(1, entry(files("retry").get(0)).getRetryCount());
        }
    }

    @Test
    void testRetryableStatuses() {
        assertTrue(BotEndpointCaller.isRetryableStatus(408));
        assertTrue(BotEndpointCaller.isRetryableStatus(429));
        assertTrue(BotEndpointCaller.isRetryableStatus(500));
        assertTrue(BotEndpointCaller.isRetryableStatus(503));
        assertFalse(BotEndpointCaller.isRetryableStatus(400));
        assertFalse(BotEndpointCaller.isRetryableStatus(401));
        assertFalse(BotEndpointCaller.isRetryableStatus(422));
    }
}
