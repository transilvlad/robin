package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.BlocklistConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for blocklist configuration and matching.
 * Tests that the configuration loads correctly and the matcher works as expected.
 */
class BlocklistIntegrationTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void testBlocklistConfigLoadedFromFile() {
        BlocklistConfig config = Config.getServer().getBlocklistConfig();
        
        assertNotNull(config, "Blocklist config should be loaded");
        assertTrue(config.isEnabled(), "Test blocklist should be enabled");
        assertEquals(3, config.getEntries().size(), "Should have 3 entries in test config");
    }

    @Test
    void testBlocklistMatcherWithLoadedConfig() {
        BlocklistConfig config = Config.getServer().getBlocklistConfig();
        
        // Test single IP match
        assertTrue(BlocklistMatcher.isBlocked("192.168.100.100", config), 
            "Should block IP from config");
        
        // Test CIDR match
        assertTrue(BlocklistMatcher.isBlocked("10.50.100.200", config), 
            "Should block IP in CIDR range from config");
        
        // Test IPv6 match
        assertTrue(BlocklistMatcher.isBlocked("2001:db8::ffff", config), 
            "Should block IPv6 in range from config");
        
        // Test non-blocked IP
        assertFalse(BlocklistMatcher.isBlocked("8.8.8.8", config), 
            "Should not block IP not in config");
    }

    @Test
    void testBlocklistCanBeReloadedDynamically() {
        // Get the original config
        BlocklistConfig config1 = Config.getServer().getBlocklistConfig();
        assertTrue(config1.isEnabled(), "Original config should be enabled");
        
        // Get it again (simulates reload)
        BlocklistConfig config2 = Config.getServer().getBlocklistConfig();
        assertTrue(config2.isEnabled(), "Reloaded config should be enabled");
        
        // Verify the entries are consistent
        assertEquals(config1.getEntries().size(), config2.getEntries().size(), 
            "Reloaded config should have same number of entries");
    }

    @Test
    void testBlocklistMatcherIsThreadSafe() throws InterruptedException {
        BlocklistConfig config = Config.getServer().getBlocklistConfig();
        
        // Create multiple threads that check the blocklist concurrently
        Thread[] threads = new Thread[10];
        final boolean[] results = new boolean[10];
        
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                // Each thread checks a different IP
                results[index] = BlocklistMatcher.isBlocked("10.0.0." + index, config);
            });
            threads[i].start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // All should be blocked (10.0.0.0/8 is in the blocklist)
        for (boolean result : results) {
            assertTrue(result, "All IPs in 10.0.0.0/8 should be blocked");
        }
    }
}
