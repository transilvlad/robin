package com.mimecast.robin.storage.rocksdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@ResourceLock(value = "rocksdbjni", mode = ResourceAccessMode.READ_WRITE)
class RocksDbMailboxStoreTest {

    private RocksDbMailboxStore store;
    private Path dbPath;

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) {
            store.close();
        }
        if (dbPath != null && Files.exists(dbPath)) {
            try (var walk = Files.walk(dbPath)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void queryByUserFolderAndStateAndManageFolders() throws Exception {
        dbPath = Files.createTempDirectory("robin-rocksdb-store-");
        store = new RocksDbMailboxStore(dbPath.toString(), "Inbox", "Sent");

        var first = store.storeInbound("tony@example.com", eml("First"), "first.eml", headers("First"));
        var second = store.storeInbound("tony@example.com", eml("Second"), "second.eml", headers("Second"));
        var outbound = store.storeOutbound("tony@example.com", eml("Sent"), "sent.eml", headers("Sent"));

        store.createFolder("example.com", "tony", "", "Projects");
        store.createFolder("example.com", "tony", "Projects", "2026");
        store.moveMessages("example.com", "tony", "Inbox", "Projects", List.of(second.id));
        store.updateReadStatus("example.com", "tony", "Projects", "read", List.of(second.id));

        var mailbox = store.getMailbox("example.com", "tony", null);
        assertEquals(3, mailbox.messages.size());
        assertTrue(mailbox.folders.stream().anyMatch(folder -> "Inbox".equals(folder.path)));
        assertTrue(mailbox.folders.stream().anyMatch(folder -> "Sent".equals(folder.path)));
        assertTrue(mailbox.folders.stream().anyMatch(folder -> "Projects".equals(folder.path)));

        var inboxUnread = store.getFolder("example.com", "tony", "Inbox", "unread");
        assertEquals(1, inboxUnread.messages.size());
        assertEquals(first.id, inboxUnread.messages.getFirst().id);
        assertEquals(1, inboxUnread.properties.unread);

        var projectsRead = store.getFolder("example.com", "tony", "Projects", "read");
        assertEquals(1, projectsRead.messages.size());
        assertEquals(second.id, projectsRead.messages.getFirst().id);
        assertEquals(1, projectsRead.properties.read);

        var sentFolder = store.getFolder("example.com", "tony", "Sent", "read");
        assertEquals(1, sentFolder.messages.size());
        assertEquals(outbound.id, sentFolder.messages.getFirst().id);
        assertTrue(sentFolder.messages.getFirst().read);

        store.copyFolder("example.com", "tony", "Projects", "", "Archive");
        var archive = store.getFolder("example.com", "tony", "Archive", "read");
        assertEquals(1, archive.messages.size());
        assertEquals("Projects", mailbox.folders.stream().filter(folder -> "Projects".equals(folder.path)).findFirst().orElseThrow().path);

        store.renameFolder("example.com", "tony", "Archive", "Archive-2026");
        assertEquals("Archive-2026", store.getFolder("example.com", "tony", "Archive-2026", null).folder);

        store.deleteAllMessages("example.com", "tony", "Archive-2026");
        store.deleteFolder("example.com", "tony", "Archive-2026");
        var afterDelete = store.getMailbox("example.com", "tony", null);
        assertFalse(afterDelete.folders.stream().anyMatch(folder -> "Archive-2026".equals(folder.path)));

        var message = store.getMessage("example.com", "tony", first.id).orElseThrow();
        assertTrue(message.content.contains("Subject: First"));
    }

    @Test
    void deleteAllOnlyAffectsRequestedFolder() throws Exception {
        dbPath = Files.createTempDirectory("robin-rocksdb-delete-");
        store = new RocksDbMailboxStore(dbPath.toString(), "Inbox", "Sent");

        var inboxMessage = store.storeInbound("tony@example.com", eml("Inbox"), "inbox.eml", headers("Inbox"));
        var recentMessage = store.storeInbound("tony@example.com", eml("Recent"), "recent.eml", headers("Recent"));

        store.createFolder("example.com", "tony", "", "Projects");
        store.moveMessages("example.com", "tony", "Inbox", "Projects", List.of(recentMessage.id));

        assertEquals(1, store.deleteAllMessages("example.com", "tony", "Projects"));
        assertTrue(store.getMessage("example.com", "tony", inboxMessage.id).isPresent());
        assertFalse(store.getMessage("example.com", "tony", recentMessage.id).isPresent());
    }

    private byte[] eml(String subject) {
        return ("Subject: " + subject + "\r\nFrom: sender@example.com\r\nTo: tony@example.com\r\n\r\nBody")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> headers(String subject) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Subject", subject);
        headers.put("From", "sender@example.com");
        headers.put("To", "tony@example.com");
        return headers;
    }
}
