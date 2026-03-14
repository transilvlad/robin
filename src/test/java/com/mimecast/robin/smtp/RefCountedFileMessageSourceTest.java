package com.mimecast.robin.smtp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RefCountedFileMessageSource.
 */
class RefCountedFileMessageSourceTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private static final String TEST_CONTENT = "Test email content for reference counting";

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test-message.eml");
        Files.writeString(testFile, TEST_CONTENT);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up any remaining files.
        if (testFile != null && Files.exists(testFile)) {
            Files.deleteIfExists(testFile);
        }
    }

    @Test
    void testInitialRefCountIsOne() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);
        assertEquals(1, source.getRefCount());
    }

    @Test
    void testAcquireIncrementsRefCount() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        source.acquire();
        assertEquals(2, source.getRefCount());

        source.acquire();
        assertEquals(3, source.getRefCount());
    }

    @Test
    void testReleaseDecrementsRefCount() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);
        source.acquire(); // refCount = 2

        source.release(); // refCount = 1
        assertEquals(1, source.getRefCount());
        assertTrue(Files.exists(testFile), "File should still exist when refCount > 0");
    }

    @Test
    void testFileDeletedWhenRefCountReachesZero() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);
        assertTrue(Files.exists(testFile), "File should exist initially");

        source.release(); // refCount = 0
        assertEquals(0, source.getRefCount());
        assertFalse(Files.exists(testFile), "File should be deleted when refCount reaches 0");
    }

    @Test
    void testMultipleAcquireAndRelease() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        // Simulate multiple consumers acquiring references.
        source.acquire(); // refCount = 2
        source.acquire(); // refCount = 3
        source.acquire(); // refCount = 4
        assertEquals(4, source.getRefCount());
        assertTrue(Files.exists(testFile));

        // Simulate consumers releasing references.
        source.release(); // refCount = 3
        source.release(); // refCount = 2
        source.release(); // refCount = 1
        assertEquals(1, source.getRefCount());
        assertTrue(Files.exists(testFile), "File should still exist with refCount = 1");

        // Last consumer releases.
        source.release(); // refCount = 0
        assertEquals(0, source.getRefCount());
        assertFalse(Files.exists(testFile), "File should be deleted when last consumer releases");
    }

    @Test
    void testOpenStreamWorksBeforeRelease() throws IOException {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        try (InputStream is = source.openStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(TEST_CONTENT, content);
        }
    }

    @Test
    void testReadAllBytesWorksBeforeRelease() throws IOException {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        byte[] bytes = source.readAllBytes();
        assertEquals(TEST_CONTENT, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void testSizeReturnsCorrectValue() throws IOException {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        assertEquals(TEST_CONTENT.length(), source.size());
    }

    @Test
    void testGetPathReturnsCorrectPath() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        assertEquals(testFile, source.getPath());
    }

    @Test
    void testAcquireReturnsThis() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        MessageSource result = source.acquire();
        assertSame(source, result);
    }

    @Test
    void testConcurrentAccessScenario() throws IOException {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(testFile);

        // Main thread acquires for bot threads.
        source.acquire(); // For bot 1.
        source.acquire(); // For bot 2.
        assertEquals(3, source.getRefCount());

        // Bot 1 reads and releases.
        try (InputStream is = source.openStream()) {
            assertNotNull(is.readAllBytes());
        }
        source.release();
        assertEquals(2, source.getRefCount());
        assertTrue(Files.exists(testFile));

        // Bot 2 reads and releases.
        try (InputStream is = source.openStream()) {
            assertNotNull(is.readAllBytes());
        }
        source.release();
        assertEquals(1, source.getRefCount());
        assertTrue(Files.exists(testFile));

        // Main thread releases.
        source.release();
        assertEquals(0, source.getRefCount());
        assertFalse(Files.exists(testFile));
    }

    @Test
    void testNullPathHandledGracefully() {
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(null);

        assertNull(source.getPath());
        // Release should not throw even with null path.
        assertDoesNotThrow(source::release);
    }
}

