package com.mimecast.robin.endpoints;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for StorageDirectoryListing class.
 */
class StorageDirectoryListingTest {

    private StorageDirectoryListing listing;
    private Path testBasePath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        testBasePath = tempDir.resolve("storage");
        Files.createDirectories(testBasePath);
        listing = new StorageDirectoryListing("/store");
    }

    @Test
    void testGenerateItemsForEmptyDirectory() throws IOException {
        String items = listing.generateItems(testBasePath, "");
        assertNotNull(items);
        assertEquals("", items.trim());
    }

    @Test
    void testGenerateItemsWithEmlFile() throws IOException {
        // Create a test .eml file
        Path emlFile = testBasePath.resolve("test.eml");
        Files.write(emlFile, "From: test@example.com\r\nSubject: Test\r\n\r\nTest content".getBytes());

        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertTrue(items.contains("test.eml"));
        assertTrue(items.contains("📧")); // File icon
        assertTrue(items.contains("bytes")); // File size info
    }

    @Test
    void testGenerateItemsWithDirectoryContainingEmlFile() throws IOException {
        // Create a subdirectory with an .eml file
        Path subDir = testBasePath.resolve("subdir");
        Files.createDirectories(subDir);
        Path emlFile = subDir.resolve("message.eml");
        Files.write(emlFile, "From: test@example.com\r\nSubject: Test\r\n\r\nTest content".getBytes());

        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertTrue(items.contains("subdir"));
        assertTrue(items.contains("📁")); // Directory icon
    }

    @Test
    void testGenerateItemsIncludesEmptyDirectory() throws IOException {
        // Create an empty subdirectory
        Path emptyDir = testBasePath.resolve("empty");
        Files.createDirectories(emptyDir);

        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertTrue(items.contains("empty"));
    }

    @Test
    void testGenerateItemsIncludesMaildirInternalDirectories() throws IOException {
        Files.createDirectories(testBasePath.resolve("new"));
        Files.createDirectories(testBasePath.resolve("cur"));
        Files.createDirectories(testBasePath.resolve("tmp"));
        Files.createDirectories(testBasePath.resolve(".Reports"));

        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertTrue(items.contains(">new<"));
        assertTrue(items.contains(">cur<"));
        assertTrue(items.contains(">tmp<"));
        assertTrue(items.contains(".Reports"));
    }

    @Test
    void testGenerateItemsIgnoresNonEmlFiles() throws IOException {
        // Create a non-.eml file
        Path txtFile = testBasePath.resolve("readme.txt");
        Files.write(txtFile, "This is a readme file".getBytes());

        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertFalse(items.contains("readme.txt")); // Should not include non-.eml files
    }

    @Test
    void testGenerateItemsWithParentLink() throws IOException {
        // Create subdirectory
        Path subDir = testBasePath.resolve("subdir");
        Files.createDirectories(subDir);

        String items = listing.generateItems(subDir, "subdir");

        assertNotNull(items);
        assertTrue(items.contains(".. (parent)")); // Should include parent link for subdirectories
    }

    @Test
    void testGenerateItemsNoParentLinkForRoot() throws IOException {
        String items = listing.generateItems(testBasePath, "");

        assertNotNull(items);
        assertFalse(items.contains(".. (parent)")); // Should not include parent link for root
    }
}
