package com.mimecast.robin.util;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;

/**
 * JUnit 5 extension that provides a mock Vault server for testing.
 *
 * <p>This extension starts a MockWebServer that simulates HashiCorp Vault responses
 * for testing purposes. It automatically starts before all tests and shuts down after.
 */
public class VaultClientMockExtension implements BeforeAllCallback, AfterAllCallback {

    private static MockWebServer mockServer;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        mockServer = new MockWebServer();
        mockServer.start(8200);

        // Setup mock responses
        setupMockResponses();
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    /**
     * Setup mock responses for various Vault endpoints.
     */
    private void setupMockResponses() throws IOException {
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
