package com.mimecast.robin.endpoints;

import com.mimecast.robin.config.server.EndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic test for ApiEndpoint /store functionality.
 * Minimal testing to ensure the endpoint starts without errors.
 */
class ApiEndpointStoreTest {

    private ApiEndpoint apiEndpoint;

    @BeforeEach
    void setUp() {
        apiEndpoint = new ApiEndpoint();
    }

    @Test
    void testApiEndpointCanStart() {
        // Test that the endpoint can be created and configured.
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", 8091); // Use different port to avoid conflicts.
        configMap.put("authType", "none");

        EndpointConfig config = new EndpointConfig(configMap);

        // This should not throw an exception.
        assertDoesNotThrow(() -> {
            try {
                apiEndpoint.start(config);
                Thread.sleep(100); // Give it a moment to start.
            } catch (Exception e) {
                fail("ApiEndpoint should start without errors: " + e.getMessage());
            }
        });
    }

    @Test
    void testStorageDirectoryListingCreation() {
        // Test that StorageDirectoryListing can be created successfully.
        assertDoesNotThrow(() -> {
            StorageDirectoryListing listing = new StorageDirectoryListing("/store");
            assertNotNull(listing, "StorageDirectoryListing should be created successfully");
        });
    }
}
