package com.mimecast.robin.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claiming one item by UID: only a READY item can be taken, whatever its next attempt time,
 * and once taken no worker can claim it.
 */
class QueueClaimByUIDTest {

    @TempDir
    Path tempDir;

    @Test
    void inMemoryClaimsOnlyReadyItems() {
        assertClaimsOnlyReadyItems(new InMemoryQueueDatabase<>());
    }

    @Test
    void mapDbClaimsOnlyReadyItemsAndKeepsTheClaimAcrossReopening() {
        Path file = tempDir.resolve("claim-by-uid.db");
        MapDBQueueDatabase<String> database = new MapDBQueueDatabase<>(file.toFile(), 1);
        database.initialize();
        try {
            assertClaimsOnlyReadyItems(database);
        } finally {
            database.close();
        }

        database = new MapDBQueueDatabase<>(file.toFile(), 1);
        database.initialize();
        try {
            assertEquals(QueueItemState.CLAIMED, database.getByUID("future").getState());
            assertEquals("admin", database.getByUID("future").getClaimOwner());
            assertEquals(2, database.stats().claimedCount());
        } finally {
            database.close();
        }
    }

    private static void assertClaimsOnlyReadyItems(QueueDatabase<String> database) {
        database.enqueue(QueueItem.restore("busy", 100, "busy payload").readyAt(1));
        assertEquals(1, database.claimReady(1, 2, "worker", 1000).size());
        database.enqueue(QueueItem.restore("future", 101, "future payload").readyAt(5000));
        database.enqueue(QueueItem.restore("dead", 102, "dead payload").readyAt(1));
        database.markDead("dead", "permanent failure");

        assertNull(database.claimByUID("busy", "admin", 3000), "A worker's claim is not taken over");
        assertEquals("worker", database.getByUID("busy").getClaimOwner());
        assertNull(database.claimByUID("dead", "admin", 3000), "A dead item is not revived");
        assertEquals(QueueItemState.DEAD, database.getByUID("dead").getState());
        assertNull(database.claimByUID("missing", "admin", 3000));

        QueueItem<String> claimed = database.claimByUID("future", "admin", 3000);
        assertNotNull(claimed, "A READY item is claimed even before its next attempt");
        assertEquals("future payload", claimed.getPayload());
        assertEquals(QueueItemState.CLAIMED, claimed.getState());
        assertEquals("admin", claimed.getClaimOwner());
        assertEquals(3000, claimed.getClaimedUntilEpochSeconds());
        assertNull(database.claimByUID("future", "admin", 3000), "A claim is taken once");
        assertTrue(database.claimReady(10, 10_000, "worker", 20_000).isEmpty(),
                "Workers cannot claim an item an administrator holds");
        assertEquals(2, database.stats().claimedCount());
        assertEquals(2, database.size());
    }
}
