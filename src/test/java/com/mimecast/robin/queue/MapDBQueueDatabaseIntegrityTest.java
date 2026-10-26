package com.mimecast.robin.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDBQueueDatabaseIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void scheduledItemsAndIndexesSurviveReopening() {
        Path queueFile = tempDir.resolve("test-persistent-queue.db");
        MapDBQueueDatabase<String> database = new MapDBQueueDatabase<>(queueFile.toFile(), 1);
        database.initialize();
        try {
            database.enqueue(QueueItem.restore("claimed", 100, "claimed payload").readyAt(1));
            database.claimReady(1, 2, "consumer", 1000);
            database.enqueue(QueueItem.restore("ready", 101, "ready payload").readyAt(3));
            database.enqueue(QueueItem.restore("dead", 102, "dead payload").readyAt(4));
            database.markDead("dead", "permanent failure");
        } finally {
            database.close();
        }

        database = new MapDBQueueDatabase<>(queueFile.toFile(), 1);
        database.initialize();
        try {
            assertEquals("ready payload", database.getByUID("ready").getPayload());
            assertEquals(QueueItemState.CLAIMED, database.getByUID("claimed").getState());
            assertEquals("consumer", database.getByUID("claimed").getClaimOwner());
            assertEquals(QueueItemState.DEAD, database.getByUID("dead").getState());
            assertEquals("permanent failure", database.getByUID("dead").getLastError());
            assertEquals(1, database.stats().readyCount());
            assertEquals(1, database.stats().claimedCount());
            assertEquals(1, database.stats().deadCount());
            assertEquals(1, database.releaseExpiredClaims(1001));
            assertEquals(2, database.claimReady(10, 1001, "next", 2000).size());
            assertTrue(database.acknowledge("ready"));
            assertTrue(database.acknowledge("claimed"));
            assertEquals(0, database.size());
        } finally {
            database.close();
        }
    }

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
