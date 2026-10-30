package com.mimecast.robin.main;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.config.ConfigSourceFingerprint;
import com.mimecast.robin.config.store.ConfigStoreSyncManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class ConfigReloadTest {
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws Exception {
        ConfigStoreSyncManager.setConfigDir(directory.toString());
        Files.writeString(directory.resolve("properties.json5"),
                "{propertiesAutoReload:{enabled:false},serverAutoReload:{enabled:false}}");
        Files.writeString(directory.resolve("server.json5"), "{hostname:'before.test'}");
        Config.initProperties(directory.resolve("properties.json5").toString());
        Config.initServer(directory.resolve("server.json5").toString());
    }

    @AfterEach
    void restore() throws Exception {
        ConfigLoader.load("src/test/resources/cfg/");
    }

    @Test
    void manualReloadWorksWithoutAutomaticSchedulingAndReportsRestartBoundary() throws Exception {
        String source = "{hostname:'after.test',submissionConfig:{messageSizeLimit:20971520}}";
        Files.writeString(directory.resolve("server.json5"), source);
        Files.writeString(directory.resolve("rspamd.json5"), "{enabled:true,timeout:17}");

        Map<String, Object> response = Config.triggerReload();

        assertEquals(true, response.get("applied"));
        assertEquals("configuration-snapshot", response.get("scope"));
        assertEquals(true, response.get("restartRequired"));
        assertEquals("after.test", Config.getServer().getHostname());
        assertEquals(20971520, Config.getServer().getSubmissionConfig().getEmailSizeLimit());
        assertEquals(17, Config.getServer().getRspamd().getTimeout());
        Map<?, ?> sources = (Map<?, ?>) response.get("sourceFiles");
        assertEquals(ConfigSourceFingerprint.sha256(source), ((Map<?, ?>) sources.get("server.json5")).get("sha256"));
        assertNotNull(sources.get("rspamd.json5"));
        assertFalse(response.toString().contains("hostname"));
    }

    @Test
    void invalidExternalFileDoesNotPublishHalfReloadedSnapshot() throws Exception {
        var originalProperties = Config.getProperties();
        var originalServer = Config.getServer();
        Files.writeString(directory.resolve("properties.json5"), "{newValue:true}");
        Files.writeString(directory.resolve("server.json5"), "{hostname:'should-not-apply.test'}");
        Files.writeString(directory.resolve("rspamd.json5"), "{broken:");

        assertThrows(RuntimeException.class, Config::triggerReload);

        assertSame(originalProperties, Config.getProperties());
        assertSame(originalServer, Config.getServer());
        assertEquals("before.test", Config.getServer().getHostname());
    }
}
