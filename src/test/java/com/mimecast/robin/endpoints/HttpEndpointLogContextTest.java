package com.mimecast.robin.endpoints;

import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpEndpointLogContextTest {

    @Test
    void eachRequestStartsWithACleanLogContext() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpEndpoint.createServer(0);
        server.createContext("/label", exchange -> {
            ThreadContext.put("aCode", "smtp-session");
            ThreadContext.put("cCode", "tony@example.com");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/read", exchange -> {
            seen.set(ThreadContext.get("aCode") + "|" + ThreadContext.get("cCode"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            client.send(HttpRequest.newBuilder(URI.create(base + "/label")).build(), HttpResponse.BodyHandlers.discarding());
            client.send(HttpRequest.newBuilder(URI.create(base + "/read")).build(), HttpResponse.BodyHandlers.discarding());

            assertEquals("|", seen.get());
        } finally {
            server.stop(0);
        }
    }
}
