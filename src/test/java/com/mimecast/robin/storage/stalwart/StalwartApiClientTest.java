package com.mimecast.robin.storage.stalwart;

import com.mimecast.robin.config.StalwartConfig;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StalwartApiClientTest {

    @AfterEach
    void tearDown() {
        StalwartApiClient.resetSharedForTest();
    }

    @Test
    void sharedClientRefreshesWhenConfigChanges() {
        StalwartApiClient first = StalwartApiClient.shared(config("http://127.0.0.1:8080", "secret-one", "0"), false);
        StalwartApiClient second = StalwartApiClient.shared(config("http://127.0.0.1:8080", "secret-one", "0"), false);
        StalwartApiClient third = StalwartApiClient.shared(config("http://127.0.0.1:8080", "secret-two", "0"), false);

        assertSame(first, second);
        assertNotSame(first, third);
    }

    @Test
    void deliverUsesConfiguredInboxMailboxIdAndWarmCaches() throws Exception {
        AtomicInteger sessionRequests = new AtomicInteger();
        AtomicInteger principalQueries = new AtomicInteger();
        AtomicInteger mailboxQueries = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger imports = new AtomicInteger();

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if ("GET".equals(request.getMethod()) && "/jmap/session".equals(path)) {
                        sessionRequests.incrementAndGet();
                        return json("""
                                {
                                  "apiUrl": "/jmap/api",
                                  "uploadUrl": "/jmap/upload/{accountId}"
                                }
                                """);
                    }
                    if ("POST".equals(request.getMethod()) && "/jmap/api".equals(path)) {
                        String body = request.getBody().readUtf8();
                        if (body.contains("\"Principal/query\"")) {
                            principalQueries.incrementAndGet();
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Principal/query", {"ids": ["acc-1"]}, "principal-query"]
                                      ]
                                    }
                                    """);
                        }
                        if (body.contains("\"Mailbox/query\"")) {
                            mailboxQueries.incrementAndGet();
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Mailbox/query", {"ids": ["0"]}, "mailbox-query"]
                                      ]
                                    }
                                    """);
                        }
                        if (body.contains("\"Email/import\"")) {
                            imports.incrementAndGet();
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Email/import", {"created": {"import-1": {"id": "msg-1"}}}, "email-import"]
                                      ]
                                    }
                                    """);
                        }
                    }
                    if ("POST".equals(request.getMethod()) && "/jmap/upload/acc-1".equals(path)) {
                        uploads.incrementAndGet();
                        return json("{\"blobId\":\"blob-" + uploads.get() + "\"}");
                    }

                    return new MockResponse().setResponseCode(500);
                }
            });
            server.start();

            StalwartApiClient client = new StalwartApiClient(
                    config(server.url("/").toString(), "secret-one", "0"),
                    false
            );
            byte[] rawMessage = "From: sender@example.com\r\nTo: user01@example.com\r\nSubject: Test\r\n\r\nBody\r\n"
                    .getBytes(StandardCharsets.UTF_8);

            Map<String, String> firstFailures = client.deliverToRecipients(rawMessage, List.of("user01@example.com"));
            Map<String, String> secondFailures = client.deliverToRecipients(rawMessage, List.of("user01@example.com"));

            assertTrue(firstFailures.isEmpty());
            assertTrue(secondFailures.isEmpty());
            assertEquals(1, sessionRequests.get());
            assertEquals(1, principalQueries.get());
            assertEquals(0, mailboxQueries.get());
            assertEquals(2, uploads.get());
            assertEquals(2, imports.get());
        }
    }

    @Test
    void deliverQueriesInboxMailboxIdWhenNotConfigured() throws Exception {
        AtomicInteger sessionRequests = new AtomicInteger();
        AtomicInteger principalQueries = new AtomicInteger();
        AtomicInteger mailboxQueries = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger imports = new AtomicInteger();

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if ("GET".equals(request.getMethod()) && "/jmap/session".equals(path)) {
                        sessionRequests.incrementAndGet();
                        return json("""
                                {
                                  "apiUrl": "/jmap/api",
                                  "uploadUrl": "/jmap/upload/{accountId}"
                                }
                                """);
                    }
                    if ("POST".equals(request.getMethod()) && "/jmap/api".equals(path)) {
                        String body = request.getBody().readUtf8();
                        if (body.contains("\"Principal/query\"")) {
                            principalQueries.incrementAndGet();
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Principal/query", {"ids": ["acc-1"]}, "principal-query"]
                                      ]
                                    }
                                    """);
                        }
                        if (body.contains("\"Mailbox/query\"")) {
                            mailboxQueries.incrementAndGet();
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Mailbox/query", {"ids": ["mailbox-a"]}, "mailbox-query"]
                                      ]
                                    }
                                    """);
                        }
                        if (body.contains("\"Email/import\"")) {
                            imports.incrementAndGet();
                            assertTrue(body.contains("\"mailbox-a\""));
                            return json("""
                                    {
                                      "methodResponses": [
                                        ["Email/import", {"created": {"import-1": {"id": "msg-1"}}}, "email-import"]
                                      ]
                                    }
                                    """);
                        }
                    }
                    if ("POST".equals(request.getMethod()) && "/jmap/upload/acc-1".equals(path)) {
                        uploads.incrementAndGet();
                        return json("{\"blobId\":\"blob-" + uploads.get() + "\"}");
                    }

                    return new MockResponse().setResponseCode(500);
                }
            });
            server.start();

            StalwartApiClient client = new StalwartApiClient(
                    config(server.url("/").toString(), "secret-one", ""),
                    false
            );
            byte[] rawMessage = "From: sender@example.com\r\nTo: user01@example.com\r\nSubject: Test\r\n\r\nBody\r\n"
                    .getBytes(StandardCharsets.UTF_8);

            Map<String, String> firstFailures = client.deliverToRecipients(rawMessage, List.of("user01@example.com"));
            Map<String, String> secondFailures = client.deliverToRecipients(rawMessage, List.of("user01@example.com"));

            assertTrue(firstFailures.isEmpty());
            assertTrue(secondFailures.isEmpty());
            assertEquals(1, sessionRequests.get());
            assertEquals(1, principalQueries.get());
            assertEquals(1, mailboxQueries.get());
            assertEquals(2, uploads.get());
            assertEquals(2, imports.get());
        }
    }

    private static StalwartConfig config(String baseUrl, String password, String inboxMailboxId) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("baseUrl", baseUrl);
        map.put("username", "admin");
        map.put("password", password);
        map.put("connectTimeoutSeconds", 1);
        map.put("readTimeoutSeconds", 1);
        map.put("writeTimeoutSeconds", 1);
        map.put("lookupCacheTtlSeconds", 60);
        map.put("lookupCacheMaxEntries", 32);
        map.put("maxConcurrentRequests", 4);
        map.put("inboxMailboxId", inboxMailboxId);
        return new StalwartConfig(map);
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
