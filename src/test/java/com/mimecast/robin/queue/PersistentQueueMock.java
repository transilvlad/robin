package com.mimecast.robin.queue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mock implementation of PersistentQueue for testing purposes.
 * <p>Uses a temporary file-based MapDB queue that is automatically cleaned up.
 * <p>This implementation creates a temporary database file and tracks operations
 * for test verification purposes.
 */
public class PersistentQueueMock extends PersistentQueue<RelaySession> {
    private final Path tempDir;
    private final File tempQueueFile;

    /**
     * Counter for number of dequeue operations performed.
     */
    public int dequeueCount = 0;

    /**
     * Counter for number of enqueue operations performed.
     */
    public int enqueueCount = 0;

    /**
     * Constructs a mock persistent queue with a temporary file.
     * Creates a unique temporary directory and database file for each test instance.
     */
    public PersistentQueueMock() {
        this(createTempQueueFile());
    }

    /**
     * Private constructor that accepts the temp file.
     *
     * @param tempFile Temporary queue file.
     */
    private PersistentQueueMock(File tempFile) {
        super(tempFile);
        this.tempQueueFile = tempFile;
        this.tempDir = tempFile.toPath().getParent();
    }

    /**
     * Creates a temporary queue file in a temporary directory.
     *
     * @return Temporary queue file.
     */
    private static File createTempQueueFile() {
        try {
            Path tmpDir = Files.createTempDirectory("robinRelayQueue-test-");
            File tmpFile = tmpDir.resolve("relayQueue-" + System.nanoTime() + ".db").toFile();
            return tmpFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temporary queue file for testing", e);
        }
    }

    /**
     * Enqueues an item and increments the enqueue counter.
     *
     * @param item RelaySession instance to enqueue.
     * @return PersistentQueue instance.
     */
    @Override
    public PersistentQueue<RelaySession> enqueue(RelaySession item) {
        enqueueCount++;
        return super.enqueue(item);
    }

    /**
     * Dequeues an item and increments the dequeue counter.
     *
     * @return RelaySession instance.
     */
    @Override
    public RelaySession dequeue() {
        dequeueCount++;
        return super.dequeue();
    }

    /**
     * Closes the queue and cleans up temporary files.
     */
    @Override
    public void close() {
        try {
            // Close the database first.
            super.close();

            // Clean up temp files.
            cleanupTempFiles();
        } catch (Exception e) {
            // Best effort cleanup.
        }
    }

    /**
     * Cleans up temporary database files and directory.
     */
    private void cleanupTempFiles() {
        try {
            // Delete the main database file
            if (tempQueueFile != null && tempQueueFile.exists()) {
                tempQueueFile.delete();
            }

            // Delete any WAL (Write-Ahead Log) files that MapDB might create.
            for (int i = 0; i < 4; i++) {
                File walFile = new File(tempQueueFile.getAbsolutePath() + ".wal." + i);
                if (walFile.exists()) {
                    walFile.delete();
                }
            }

            // Delete the parent directory if empty.
            if (tempDir != null) {
                Files.deleteIfExists(tempDir);
            }
        } catch (Exception e) {
            // Ignore cleanup errors in tests.
        }
    }
}
