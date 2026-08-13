package com.mimecast.robin.storage.rocksdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocksDbMailboxStoreConcurrencyTest {
    private RocksDbMailboxStore store;
    private Path dbPath;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = Files.createTempDirectory("robin-rocksdb-concurrency-");
        store = new RocksDbMailboxStore(dbPath.toString(), "Inbox", "Sent");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void storeInbound_parallelDelivery_sameMailbox_createsEveryMessageOnce() throws Exception {
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        deliverConcurrently(List.of("tony@example.com"), 8, 200, ids);

        var inbox = store.getFolder("example.com", "tony", "Inbox", "unread");
        assertEquals(200, inbox.messages.size());
        assertEquals(200, ids.size());
    }

    @Test
    void storeInbound_parallelDelivery_differentMailboxes_keepsMailboxesIsolated() throws Exception {
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        deliverConcurrently(List.of("tony@example.com", "pepper@example.com"), 8, 80, ids);

        assertEquals(80, store.getFolder("example.com", "tony", "Inbox", "unread").messages.size());
        assertEquals(80, store.getFolder("example.com", "pepper", "Inbox", "unread").messages.size());
        assertEquals(160, ids.size());
    }

    private void deliverConcurrently(List<String> recipients, int threads, int messagesPerRecipient, Set<String> ids) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MailboxStore.MessageSummary>> futures = new ArrayList<>();
        try {
            for (String recipient : recipients) {
                for (int i = 0; i < messagesPerRecipient; i++) {
                    String subject = recipient.substring(0, recipient.indexOf('@')) + "-" + i;
                    Callable<MailboxStore.MessageSummary> task = () -> {
                        start.await();
                        return store.storeInbound(recipient, eml(subject, recipient), subject + ".eml", headers(subject, recipient));
                    };
                    futures.add(executor.submit(task));
                }
            }
            start.countDown();
            for (Future<MailboxStore.MessageSummary> future : futures) {
                ids.add(future.get().id);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private byte[] eml(String subject, String recipient) {
        return ("Subject: " + subject + "\r\nFrom: sender@example.com\r\nTo: " + recipient + "\r\n\r\nBody")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> headers(String subject, String recipient) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Subject", subject);
        headers.put("From", "sender@example.com");
        headers.put("To", recipient);
        return headers;
    }
}
