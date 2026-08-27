package com.mimecast.robin.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDBQueueDatabaseIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeFailsClearlyWhenMapDbFileIsCorrupt() throws Exception {
        Path queueFile = tempDir.resolve("test-corrupt-queue.db");
        Files.writeString(queueFile, "not a mapdb database");

        MapDBQueueDatabase<RelaySession> database = new MapDBQueueDatabase<>(
                queueFile.toFile(),
                1,
                true,
                10
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, database::initialize);
        assertTrue(thrown.getMessage().contains("MapDB queue file failed integrity check"));
        assertTrue(thrown.getMessage().contains("switch backend or rebuild queue file"));
    }
}
