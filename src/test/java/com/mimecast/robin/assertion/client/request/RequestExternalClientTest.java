package com.mimecast.robin.assertion.client.request;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.assertion.AssertConfig;
import com.mimecast.robin.http.HttpResponse;
import com.mimecast.robin.http.HttpResponseFixture;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestExternalClientTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    /**
     * Client returning queued responses instead of making HTTP calls.
     */
    static class QueuedRequestExternalClient extends RequestExternalClient {
        final Deque<HttpResponse> responses = new ArrayDeque<>();
        int requests = 0;

        @Override
        protected HttpResponse makeRequest() {
            requests++;
            return responses.size() > 1 ? responses.poll() : responses.peek();
        }
    }

    private static QueuedRequestExternalClient client(Map<String, Object> config, boolean verifyFails, HttpResponse... responses) {
        Map<String, Object> map = new HashMap<>(Map.of("type", "request", "wait", 0, "delay", 0));
        map.putAll(config);

        Session session = new Session();
        session.addAssertions(new AssertConfig(new HashMap<>(Map.of("verifyFails", verifyFails))));

        QueuedRequestExternalClient client = new QueuedRequestExternalClient();
        client.setConnection(new Connection(session));
        client.setConfig(new BasicConfig(map));
        client.responses.addAll(List.of(responses));
        return client;
    }

    private static HttpResponse text(String body) {
        return HttpResponseFixture.of(200, "text/plain", body);
    }

    @Test
    void verifyMissFailsAfterRetries() {
        QueuedRequestExternalClient client = client(Map.of("retry", 3, "verify", List.of("delivery successful")), true,
                text("queued\nnothing else"));

        AssertException e = assertThrows(AssertException.class, client::run);
        assertTrue(e.getMessage().contains("verify"));
        assertEquals(3, client.requests);
    }

    @Test
    void verifyRetriesUntilMatched() throws AssertException {
        QueuedRequestExternalClient client = client(Map.of("retry", 3, "verify", List.of("delivery successful")), true,
                text("queued"), text("LMTP delivery successful"));

        client.run();
        assertEquals(2, client.requests);
        assertFalse(client.skip());
    }

    @Test
    void verifyMissSkipsWhenNotFatal() throws AssertException {
        QueuedRequestExternalClient client = client(Map.of("retry", 2, "verify", List.of("absent text")), false,
                text("present text"));

        client.run();
        assertTrue(client.skip());
        assertEquals(2, client.requests);
    }

    @Test
    void verifyNeedsAllPatternsOnOneLine() {
        QueuedRequestExternalClient client = client(Map.of("verify", List.of("alpha", "beta")), true,
                text("alpha\nbeta"));

        assertThrows(AssertException.class, client::run);
    }

    @Test
    void verifyNonePassesOnlyWithoutData() throws AssertException {
        client(Map.of("retry", 2, "verifyNone", true), true, text("")).run();

        QueuedRequestExternalClient found = client(Map.of("verifyNone", true), true, text("unexpected line"));
        AssertException e = assertThrows(AssertException.class, found::run);
        assertTrue(e.getMessage().contains("none expected"));
    }

    @Test
    void unsuccessfulStatusFails() {
        QueuedRequestExternalClient client = client(Map.of("retry", 2), true,
                HttpResponseFixture.of(500, "text/plain", "error"));

        AssertException e = assertThrows(AssertException.class, client::run);
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void expectedStatusIsAsserted() throws AssertException {
        client(Map.of("status", 401), true, HttpResponseFixture.of(401, "text/plain", "Unauthorized")).run();

        QueuedRequestExternalClient wrong = client(Map.of("status", 401), true, text("ok"));
        AssertException e = assertThrows(AssertException.class, wrong::run);
        assertTrue(e.getMessage().contains("status 401"));
    }

    @Test
    void jsonWithoutPathsKeepsAllData() throws AssertException {
        client(Map.of("verify", List.of("UP")), true,
                HttpResponseFixture.of(200, "application/json", "{\"status\":\"UP\"}")).run();
    }

    @Test
    void jsonPathsFilterData() {
        QueuedRequestExternalClient client = client(Map.of("paths", List.of("other"), "verify", List.of("UP")), true,
                HttpResponseFixture.of(200, "application/json", "{\"status\":\"UP\",\"other\":\"x\"}"));

        assertThrows(AssertException.class, client::run);
    }

    @Test
    void refuseFailsWhenMatched() {
        QueuedRequestExternalClient client = client(Map.of("refuse", List.of(List.of("Exception"))), true,
                text("java.lang.NullPointerException"));

        assertThrows(AssertException.class, client::run);
    }
}
