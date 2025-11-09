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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientEndpoint logs functionality.
 */
class ClientEndpointLogsTest {

    private Path tempDir;
    private Path todayLogFile;
    private Path yesterdayLogFile;
    private String originalLogPattern;

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
        
        // Store original log directory and set it to temp directory for tests
        originalLogPattern = "/var/log";
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
    void testLogsEndpointNoQuery() throws IOException, URISyntaxException {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");
        config.put("port", 8090);

        ClientEndpoint endpoint = new ClientEndpoint();
        MockHttpExchange exchange = new MockHttpExchange("127.0.0.1", "GET", "/logs");

        // Use reflection to access private handleLogs method and replace log directory
        try {
            java.lang.reflect.Method searchMethod = ClientEndpoint.class.getDeclaredMethod("searchLogFile", String.class, String.class, StringBuilder.class);
            searchMethod.setAccessible(true);
            
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("handleLogs", HttpExchange.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
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
     * Tests searchLogFile method directly with test files.
     */
    @Test
    void testSearchLogFileMethod() throws IOException {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");

        ClientEndpoint endpoint = new ClientEndpoint();

        // Use reflection to access private searchLogFile method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("searchLogFile", String.class, String.class, StringBuilder.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));
            
            StringBuilder results = new StringBuilder();
            int matches = (int) method.invoke(endpoint, todayLogFile.toString(), "ERROR", results);

            assertEquals(1, matches);
            assertTrue(results.toString().contains("Connection failed"));
        } catch (Exception e) {
            fail("Failed to invoke searchLogFile method: " + e.getMessage());
        }
    }

    /**
     * Tests searchLogFile with multiple matches.
     */
    @Test
    void testSearchLogFileMultipleMatches() throws IOException {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");

        ClientEndpoint endpoint = new ClientEndpoint();

        // Use reflection to access private searchLogFile method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("searchLogFile", String.class, String.class, StringBuilder.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));
            
            StringBuilder results = new StringBuilder();
            int matches = (int) method.invoke(endpoint, todayLogFile.toString(), "Robin", results);

            assertEquals(2, matches);
            assertTrue(results.toString().contains("Starting application"));
            assertTrue(results.toString().contains("Application started successfully"));
        } catch (Exception e) {
            fail("Failed to invoke searchLogFile method: " + e.getMessage());
        }
    }

    /**
     * Tests searchLogFile with no matches.
     */
    @Test
    void testSearchLogFileNoMatches() throws IOException {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");

        ClientEndpoint endpoint = new ClientEndpoint();

        // Use reflection to access private searchLogFile method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("searchLogFile", String.class, String.class, StringBuilder.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));
            
            StringBuilder results = new StringBuilder();
            int matches = (int) method.invoke(endpoint, todayLogFile.toString(), "NONEXISTENT", results);

            assertEquals(0, matches);
        } catch (Exception e) {
            fail("Failed to invoke searchLogFile method: " + e.getMessage());
        }
    }

    /**
     * Tests that we can extract log file pattern from log4j2 configuration.
     */
    @Test
    void testGetLogFilePatternFromLog4j2() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");

        ClientEndpoint endpoint = new ClientEndpoint();

        // Use reflection to access private getLogFilePatternFromLog4j2 method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("getLogFilePatternFromLog4j2");
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));
            
            String pattern = (String) method.invoke(endpoint);

            // The pattern should be from log4j2.xml configuration
            assertNotNull(pattern, "Log file pattern should not be null");
            assertTrue(pattern.contains("robin"), "Pattern should contain 'robin'");
            assertTrue(pattern.contains("%d{yyyyMMdd}"), "Pattern should contain date format");
            assertTrue(pattern.endsWith(".log"), "Pattern should end with .log");
            
            System.out.println("Found log file pattern: " + pattern);
        } catch (Exception e) {
            fail("Failed to invoke getLogFilePatternFromLog4j2 method: " + e.getMessage());
        }
    }

    /**
     * Tests that we can extract date format from log file pattern.
     */
    @Test
    void testExtractDateFormatFromPattern() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");

        ClientEndpoint endpoint = new ClientEndpoint();

        // Use reflection to access private extractDateFormatFromPattern method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("extractDateFormatFromPattern", String.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
            authField.setAccessible(true);
            authField.set(endpoint, new HttpAuth(new EndpointConfig(config), "Test"));
            
            // Test various patterns
            String dateFormat1 = (String) method.invoke(endpoint, "/var/log/robin-%d{yyyyMMdd}.log");
            assertEquals("yyyyMMdd", dateFormat1);
            
            String dateFormat2 = (String) method.invoke(endpoint, "./log/robin-build-%d{yyyyMMdd}.log");
            assertEquals("yyyyMMdd", dateFormat2);
            
            String dateFormat3 = (String) method.invoke(endpoint, "/var/log/app-%d{yyyy-MM-dd}.log");
            assertEquals("yyyy-MM-dd", dateFormat3);
            
            // Test pattern without date
            String dateFormat4 = (String) method.invoke(endpoint, "/var/log/app.log");
            assertNull(dateFormat4);
            
            // Test null/empty patterns
            String dateFormat5 = (String) method.invoke(endpoint, (String) null);
            assertNull(dateFormat5);
            
            String dateFormat6 = (String) method.invoke(endpoint, "");
            assertNull(dateFormat6);
        } catch (Exception e) {
            fail("Failed to invoke extractDateFormatFromPattern method: " + e.getMessage());
        }
    }

    /**
     * Tests that logs endpoint rejects non-GET requests.
     */
    @Test
    void testLogsEndpointPostMethod() throws IOException {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");
        config.put("port", 8090);

        ClientEndpoint endpoint = new ClientEndpoint();
        MockHttpExchange exchange = new MockHttpExchange("127.0.0.1", "POST", "/logs?query=test");

        // Use reflection to access private handleLogs method
        try {
            java.lang.reflect.Method method = ClientEndpoint.class.getDeclaredMethod("handleLogs", HttpExchange.class);
            method.setAccessible(true);
            
            // Set auth field
            java.lang.reflect.Field authField = ClientEndpoint.class.getDeclaredField("auth");
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
