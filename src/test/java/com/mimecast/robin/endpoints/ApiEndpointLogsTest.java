package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiEndpoint logs functionality.
 */
class ApiEndpointLogsTest {

    private Path tempDir;
    private Path todayLogFile;
    private Path yesterdayLogFile;

    /**
     * Mock HttpExchange for testing logs endpoint.
     */
    private static class MockHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final InetSocketAddress remoteAddress;
        private final String requestMethod;
        private final URI requestURI;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode;

        MockHttpExchange(String remoteIp, String method, String uri) {
            this.requestMethod = method;
            try {
                this.requestURI = new URI(uri);
                this.remoteAddress = new InetSocketAddress(InetAddress.getByName(remoteIp), 12345);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public URI getRequestURI() {
            return requestURI;
        }

        @Override
        public String getRequestMethod() {
            return requestMethod;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int code, long length) throws IOException {
            this.responseCode = code;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String s) {
            return null;
        }

        @Override
        public void setAttribute(String s, Object o) {
        }

        @Override
        public void setStreams(InputStream inputStream, OutputStream outputStream) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        public String getResponseBodyString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // Create temporary directory for test log files
        tempDir = Files.createTempDirectory("robin-test-logs-");

        // Get current date and yesterday's date for log file names
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        todayLogFile = tempDir.resolve("robin-" + today.format(formatter) + ".log");
        yesterdayLogFile = tempDir.resolve("robin-" + yesterday.format(formatter) + ".log");

        // Create test log content
        String todayContent = """
                INFO|1109-120000000|main|Robin|Starting application
                ERROR|1109-120001000|worker-1|Session|Connection failed
                DEBUG|1109-120002000|main|Client|Processing request
                INFO|1109-120003000|main|Robin|Application started successfully
                """;

        String yesterdayContent = """
                INFO|1108-120000000|main|Robin|Application starting
                ERROR|1108-120001000|worker-1|Database|Connection timeout
                DEBUG|1108-120002000|main|Client|Request processed
                """;

        Files.writeString(todayLogFile, todayContent, StandardCharsets.UTF_8);
        Files.writeString(yesterdayLogFile, yesterdayContent, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up test log files
        Files.deleteIfExists(todayLogFile);
        Files.deleteIfExists(yesterdayLogFile);
        Files.deleteIfExists(tempDir);
    }

    /**
     * Tests that logs endpoint returns usage when no query parameter is provided.
     */
    @Test
    void testLogsEndpointNoQuery() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");
        config.put("port", 8090);

        ApiEndpoint endpoint = new ApiEndpoint();
        MockHttpExchange exchange = new MockHttpExchange("127.0.0.1", "GET", "/logs");

        // Use reflection to access private handleLogs method
        try {
            Method method = ApiEndpoint.class.getDeclaredMethod("handleLogs", HttpExchange.class);
            method.setAccessible(true);

            // Set auth field from parent HttpEndpoint class
            Field authField = HttpEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));

            method.invoke(endpoint, exchange);

            assertEquals(200, exchange.getResponseCode());
            String response = exchange.getResponseBodyString();
            assertTrue(response.contains("Usage:"));
            assertTrue(response.contains("/logs?query="));
        } catch (Exception e) {
            fail("Failed to invoke handleLogs method: " + e.getMessage());
        }
    }

    /**
     * Tests that logs endpoint rejects non-GET requests.
     */
    @Test
    void testLogsEndpointPostMethod() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");
        config.put("port", 8090);

        ApiEndpoint endpoint = new ApiEndpoint();
        MockHttpExchange exchange = new MockHttpExchange("127.0.0.1", "POST", "/logs?query=test");

        // Use reflection to access private handleLogs method
        try {
            Method method = ApiEndpoint.class.getDeclaredMethod("handleLogs", HttpExchange.class);
            method.setAccessible(true);

            // Set auth field from parent HttpEndpoint class
            Field authField = HttpEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));

            method.invoke(endpoint, exchange);

            assertEquals(405, exchange.getResponseCode());
            String response = exchange.getResponseBodyString();
            assertTrue(response.contains("Method Not Allowed"));
        } catch (Exception e) {
            fail("Failed to invoke handleLogs method: " + e.getMessage());
        }
    }
}
