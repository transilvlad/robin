package com.mimecast.robin.config;

import com.mimecast.robin.config.store.ConfigStoreSyncManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class ConfigStoreRequiredTest {
    @TempDir
    Path dir;

    @Test
    void requiredStoreFailsRatherThanUsingStaleConfiguration() throws Exception {
        Files.writeString(dir.resolve("config.json5"),
                "{enabled: true, required: true, tableName: 'invalid-table'}");
        ConfigStoreSyncManager.setConfigDir(dir.toString());
        try {
            assertThrows(IllegalStateException.class, ConfigStoreSyncManager::syncIfEnabled);
            assertNotNull(ConfigStoreSyncManager.getStatus().toMap().get("lastError"));
        } finally {
            ConfigStoreSyncManager.setConfigDir(null);
            ConfigStoreSyncManager.syncIfEnabled();
        }
    }

    @Test
    void optionalStorePreservesFailOpenBehavior() throws Exception {
        Files.writeString(dir.resolve("config.json5"), "{enabled: true, tableName: 'invalid-table'}");
        ConfigStoreSyncManager.setConfigDir(dir.toString());
        try {
            assertDoesNotThrow(ConfigStoreSyncManager::syncIfEnabled);
        } finally {
            ConfigStoreSyncManager.setConfigDir(null);
            ConfigStoreSyncManager.syncIfEnabled();
        }
    }
}
