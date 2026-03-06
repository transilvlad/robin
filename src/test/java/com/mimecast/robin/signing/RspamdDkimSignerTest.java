package com.mimecast.robin.signing;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RspamdDkimSigner using MockWebServer.
 */
class RspamdDkimSignerTest {

    private MockWebServer server;
    private RspamdDkimSigner signer;
    private File emailFile;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        signer = new RspamdDkimSigner("localhost", server.getPort());

        emailFile = File.createTempFile("dkim-test-", ".eml");
        Files.writeString(emailFile.toPath(), "From: sender@example.com\r\nTo: rcpt@example.com\r\n\r\nBody");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        Files.deleteIfExists(emailFile.toPath());
    }

    @Test
    void testSignReturnsValueOnSuccess() throws IOException {
        server.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(milterResponse("v=1; a=rsa-sha256; d=example.com; s=default; b=abc123"))
                .addHeader("Content-Type", "application/json"));

        Optional<String> result = signer.sign(emailFile, "example.com", "default", "FAKEKEY");

        assertTrue(result.isPresent());
        assertEquals("v=1; a=rsa-sha256; d=example.com; s=default; b=abc123", result.get());
    }

    @Test
    void testSignReturnsEmptyOnServerError() throws IOException {
        server.enqueue(new MockResponse().setStatus("HTTP/1.1 500 Internal Server Error"));

        Optional<String> result = signer.sign(emailFile, "example.com", "default", "FAKEKEY");

        assertFalse(result.isPresent());
    }

    @Test
    void testSignReturnsEmptyWhenNoMilterHeader() throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("spam", false);
        response.addProperty("score", 1.0);
        server.enqueue(new MockResponse()
                .setStatus("HTTP/1.1 200 OK")
                .setBody(response.toString())
                .addHeader("Content-Type", "application/json"));

        Optional<String> result = signer.sign(emailFile, "example.com", "default", "FAKEKEY");

        assertFalse(result.isPresent());
    }

    private String milterResponse(String sigValue) {
        JsonObject dkimEntry = new JsonObject();
        dkimEntry.addProperty("value", sigValue);
        dkimEntry.addProperty("order", 1);

        JsonObject addHeaders = new JsonObject();
        addHeaders.add("DKIM-Signature", dkimEntry);

        JsonObject milter = new JsonObject();
        milter.add("add_headers", addHeaders);

        JsonObject response = new JsonObject();
        response.addProperty("spam", false);
        response.addProperty("score", 1.0);
        response.add("milter", milter);
        return response.toString();
    }
}
