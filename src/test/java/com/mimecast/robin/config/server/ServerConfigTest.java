package com.mimecast.robin.config.server;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.config.StalwartConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        assertEquals(3, Config.getServer().getUsers().getList().size());
    }

    @Test
    void getUser() {
        // Tested in UserConfigTest.
        assertTrue(Config.getServer().getUsers().getUser("tony@example.com").isPresent());
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

    
    @Test
    void emptyExternalConfigFileFallsBackToEmptyMap() throws Exception {
        Path configDir = Files.createTempDirectory("server-config-empty-external-");
        Files.writeString(configDir.resolve("server.json5"), "{ hostname: \"example.com\" }\n");
        Files.writeString(configDir.resolve("stalwart.json5"), "");

        ServerConfig serverConfig = new ServerConfig(configDir.resolve("server.json5").toString());

        assertFalse(serverConfig.getStalwart().isEnabled());
        assertNotNull(serverConfig.getStalwart().getMap());
        assertTrue(serverConfig.getStalwart().getMap().isEmpty());
    }

    @Test
    void missingExternalConfigIsCachedUntilReloaded() throws Exception {
        Path configDir = Files.createTempDirectory("server-config-missing-external-");
        Files.writeString(configDir.resolve("server.json5"), "{ hostname: \"example.com\" }\n");

        ServerConfig serverConfig = new ServerConfig(configDir.resolve("server.json5").toString());

        StalwartConfig initial = serverConfig.getStalwart();
        assertFalse(initial.isEnabled());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<StalwartConfig>> tasks = List.of(
                    serverConfig::getStalwart,
                    serverConfig::getStalwart,
                    serverConfig::getStalwart,
                    serverConfig::getStalwart
            );
            for (var future : executor.invokeAll(tasks)) {
                assertFalse(future.get().isEnabled());
            }
        } finally {
            executor.shutdownNow();
        }

        Files.writeString(configDir.resolve("stalwart.json5"), "{ enabled: true, baseUrl: \"http://127.0.0.1:8081\" }\n");

        assertFalse(serverConfig.getStalwart().isEnabled());

        ServerConfig reloaded = new ServerConfig(configDir.resolve("server.json5").toString());
        assertTrue(reloaded.getStalwart().isEnabled());
        assertEquals("http://127.0.0.1:8081", reloaded.getStalwart().getBaseUrl());
    }
}
