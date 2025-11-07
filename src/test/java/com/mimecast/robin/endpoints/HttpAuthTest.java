package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpAuth class.
 */
class HttpAuthTest {

    /**
     * Mock HttpExchange for testing.
     */
    private static class MockHttpExchange extends HttpExchange {
        private final Headers headers = new Headers();
        private final InetSocketAddress remoteAddress;

        MockHttpExchange(String remoteIp) {
            try {
                this.remoteAddress = new InetSocketAddress(InetAddress.getByName(remoteIp), 12345);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Headers getRequestHeaders() {
            return headers;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public Headers getResponseHeaders() {
            return new Headers();
        }

        @Override
        public URI getRequestURI() {
            return null;
        }

        @Override
        public String getRequestMethod() {
            return "GET";
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
            return null;
        }

        @Override
        public OutputStream getResponseBody() {
            return null;
        }

        @Override
        public void sendResponseHeaders(int i, long l) throws IOException {
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return null;
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
            return 0;
        }
    }

    /**
     * Tests that authentication is disabled when authType is none.
     */
    @Test
    void testAuthDisabled() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "none");
        config.put("authValue", "");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        HttpExchange exchange = new MockHttpExchange("192.168.1.10");
        
        assertTrue(auth.isAuthenticated(exchange));
        assertFalse(auth.isAuthEnabled());
    }

    /**
     * Tests basic authentication with valid credentials.
     */
    @Test
    void testBasicAuthValid() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password123");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        String credentials = Base64.getEncoder().encodeToString("admin:password123".getBytes(StandardCharsets.UTF_8));
        exchange.getRequestHeaders().add("Authorization", "Basic " + credentials);
        
        assertTrue(auth.isAuthenticated(exchange));
        assertTrue(auth.isAuthEnabled());
    }

    /**
     * Tests basic authentication with invalid credentials.
     */
    @Test
    void testBasicAuthInvalid() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password123");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        String credentials = Base64.getEncoder().encodeToString("admin:wrongpassword".getBytes(StandardCharsets.UTF_8));
        exchange.getRequestHeaders().add("Authorization", "Basic " + credentials);
        
        assertFalse(auth.isAuthenticated(exchange));
    }

    /**
     * Tests bearer authentication with valid token.
     */
    @Test
    void testBearerAuthValid() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "bearer");
        config.put("authValue", "secret-token-123");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        exchange.getRequestHeaders().add("Authorization", "Bearer secret-token-123");
        
        assertTrue(auth.isAuthenticated(exchange));
        assertTrue(auth.isAuthEnabled());
    }

    /**
     * Tests bearer authentication with invalid token.
     */
    @Test
    void testBearerAuthInvalid() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "bearer");
        config.put("authValue", "secret-token-123");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        exchange.getRequestHeaders().add("Authorization", "Bearer wrong-token");
        
        assertFalse(auth.isAuthenticated(exchange));
    }

    /**
     * Tests IP allow list with exact IP match.
     */
    @Test
    void testIpAllowListExactMatch() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("127.0.0.1", "192.168.1.10"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        
        assertTrue(auth.isAuthenticated(exchange));
    }

    /**
     * Tests IP allow list with CIDR block IPv4.
     */
    @Test
    void testIpAllowListCidrIpv4() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("192.168.1.0/24"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.50");
        
        assertTrue(auth.isAuthenticated(exchange));
    }

    /**
     * Tests IP allow list with CIDR block not matching.
     */
    @Test
    void testIpAllowListCidrNotMatch() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("192.168.1.0/24"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.2.50");
        
        assertFalse(auth.isAuthenticated(exchange));
    }

    /**
     * Tests IP allow list with IPv6 localhost.
     */
    @Test
    void testIpAllowListIpv6Localhost() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("::1"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("::1");
        
        assertTrue(auth.isAuthenticated(exchange));
    }

    /**
     * Tests missing Authorization header.
     */
    @Test
    void testMissingAuthHeader() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange = new MockHttpExchange("192.168.1.10");
        
        assertFalse(auth.isAuthenticated(exchange));
    }

    /**
     * Tests empty authValue with authType set.
     */
    @Test
    void testEmptyAuthValue() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "");
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        
        assertFalse(auth.isAuthEnabled());
    }

    /**
     * Tests CIDR with /32 (single IP).
     */
    @Test
    void testCidrSingleIp() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("192.168.1.10/32"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange1 = new MockHttpExchange("192.168.1.10");
        
        assertTrue(auth.isAuthenticated(exchange1));
        
        MockHttpExchange exchange2 = new MockHttpExchange("192.168.1.11");
        
        assertFalse(auth.isAuthenticated(exchange2));
    }

    /**
     * Tests large CIDR block /8.
     */
    @Test
    void testCidrLargeBlock() {
        Map<String, Object> config = new HashMap<>();
        config.put("authType", "basic");
        config.put("authValue", "admin:password");
        config.put("allowList", Arrays.asList("10.0.0.0/8"));
        
        HttpAuth auth = new HttpAuth(new EndpointConfig(config), "Test");
        MockHttpExchange exchange1 = new MockHttpExchange("10.5.10.20");
        
        assertTrue(auth.isAuthenticated(exchange1));
        
        MockHttpExchange exchange2 = new MockHttpExchange("11.0.0.1");
        
        assertFalse(auth.isAuthenticated(exchange2));
    }
}
