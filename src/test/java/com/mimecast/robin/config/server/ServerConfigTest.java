package com.mimecast.robin.config.server;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void getBind() {
        assertEquals("::", Config.getServer().getBind());
    }

    @Test
    void getSmtpPort() {
        assertEquals(25, Config.getServer().getSmtpPort());
    }

    @Test
    void isAuth() {
        assertTrue(Config.getServer().isAuth());
    }

    @Test
    void isStartTls() {
        assertTrue(Config.getServer().isStartTls());
    }

    @Test
    void isChunking() {
        assertTrue(Config.getServer().isChunking());
    }

    @Test
    void getKeyStore() {
        assertEquals("src/test/resources/keystore.jks", Config.getServer().getKeyStore());
    }

    @Test
    void getKeyStorePassword() {
        assertEquals("avengers", Config.getServer().getKeyStorePassword());
    }

    @Test
    void getUsers() {
        assertEquals(1, Config.getServer().getUsers().size());
    }

    @Test
    void getUser() {
        // Tested in UserConfigTest.
        assertTrue(Config.getServer().getUser("tony@example.com").isPresent());
    }

    @Test
    void getScenarios() {
        // Tested in ScenarioConfigTest.
        assertFalse(Config.getServer().getScenarios().isEmpty());
    }

    @Test
    void getBlocklistConfig() {
        BlocklistConfig blocklistConfig = Config.getServer().getBlocklistConfig();
        assertNotNull(blocklistConfig, "Blocklist config should not be null");
        assertTrue(blocklistConfig.isEnabled(), "Blocklist should be enabled in test config");
        assertEquals(3, blocklistConfig.getEntries().size(), "Should have 3 blocklist entries");
        assertTrue(blocklistConfig.getEntries().contains("192.168.100.100"), "Should contain test IP");
        assertTrue(blocklistConfig.getEntries().contains("10.0.0.0/8"), "Should contain test CIDR");
    }

    @Test
    void isXclientEnabled() {
        // Test config has xclientEnabled set to true for testing
        assertTrue(Config.getServer().isXclientEnabled(), "XCLIENT should be enabled in test config");
    }
}
