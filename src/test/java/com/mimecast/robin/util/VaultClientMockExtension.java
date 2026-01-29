package com.mimecast.robin.util;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JUnit 5 extension that provides a mock Vault server for testing.
 *
 * <p>This extension starts a MockWebServer that simulates HashiCorp Vault responses
 * for testing purposes. It automatically starts before all tests and shuts down after.
 * <p>Each test class gets its own server instance on a dynamically allocated port to avoid conflicts.
 */
public class VaultClientMockExtension implements BeforeAllCallback, AfterAllCallback {

    private static final ConcurrentHashMap<Class<?>, MockWebServer> servers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Integer> ports = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        
        // Create a new server for this test class
        MockWebServer mockServer = new MockWebServer();
        // Use port 0 to let the OS assign an available port dynamically
        mockServer.start();

        // Store server and port
        servers.put(testClass, mockServer);
        ports.put(testClass, mockServer.getPort());

        // Setup mock responses
        setupMockResponses(mockServer);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        MockWebServer mockServer = servers.remove(testClass);
        ports.remove(testClass);
        
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    /**
     * Get the dynamically allocated port for the mock server.
     * This should be called from test methods to get the port for the current test class.
     *
     * @return The port number, or 8200 if not available.
     */
    public static Integer getMockServerPort() {
        // Find the port for any test class (there should only be one running at a time per thread)
        // In case of parallel execution, we return the first available port
        if (!ports.isEmpty()) {
            return ports.values().iterator().next();
        }
        return 8200;
    }

    /**
     * Setup mock responses for various Vault endpoints.
     */
    private void setupMockResponses(MockWebServer mockServer) throws IOException {
        // Mock dispatcher to handle different paths
        mockServer.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                String path = request.getPath();
                String method = request.getMethod();

                // KV v2 - secret/data/myapp/config
                if (path.equals("/v1/secret/data/myapp/config") && method.equals("GET")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(getKvV2Response())
                            .addHeader("Content-Type", "application/json");
                }

                // KV v1 - secret/database
                if (path.equals("/v1/secret/database") && method.equals("GET")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(getKvV1Response())
                            .addHeader("Content-Type", "application/json");
                }

                // Write secret
                if (path.equals("/v1/secret/data/myapp/api") && method.equals("POST")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody("{\"request_id\":\"abc-123\",\"lease_id\":\"\",\"renewable\":false,\"lease_duration\":0,\"data\":{\"created_time\":\"2025-10-23T10:00:00.000Z\",\"deletion_time\":\"\",\"destroyed\":false,\"version\":1}}")
                            .addHeader("Content-Type", "application/json");
                }

                // Default 404 for unknown paths
                return new MockResponse()
                        .setResponseCode(404)
                        .setBody("{\"errors\":[\"path not found\"]}")
                        .addHeader("Content-Type", "application/json");
            }
        });
    }

    /**
     * Returns a mock KV v2 response.
     *
     * @return JSON response string.
     */
    private String getKvV2Response() {
        return """
                {
                  "request_id": "abc-123",
                  "lease_id": "",
                  "renewable": false,
                  "lease_duration": 0,
                  "data": {
                    "data": {
                      "password": "superSecretPassword123",
                      "username": "admin",
                      "hostname": "myapp.example.com"
                    },
                    "metadata": {
                      "created_time": "2025-10-23T10:00:00.000Z",
                      "deletion_time": "",
                      "destroyed": false,
                      "version": 1
                    }
                  }
                }
                """;
    }

    /**
     * Returns a mock KV v1 response.
     *
     * @return JSON response string.
     */
    private String getKvV1Response() {
        return """
                {
                  "request_id": "def-456",
                  "lease_id": "",
                  "renewable": false,
                  "lease_duration": 2764800,
                  "data": {
                    "username": "dbuser",
                    "password": "dbpass123",
                    "host": "db.example.com"
                  }
                }
                """;
    }
}
