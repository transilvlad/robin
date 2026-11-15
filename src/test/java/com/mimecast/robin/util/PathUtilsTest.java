package com.mimecast.robin.util;

import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathUtilsTest {
    @TempDir
    Path tempDir;

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void isFile() {
        assertTrue(PathUtils.isFile("src/test/resources/cfg/properties.json5"));
    }

    @Test
    void isNotFile() {
        assertFalse(PathUtils.isFile("src/test/resources/not.file"));
    }

    @Test
    void cleanFilePath() {
        String random = "¬!\"£$%^&*()_+Q{}:@~|<>?`-=[];'#\\,./";
        assertEquals("¬!\"£$%^&*()_+Q{}:@~|<>?`-=[];'#,.", PathUtils.normalize(random));
    }

    @Test
    void makePath() {
        String path = tempDir.resolve(Long.toString(System.nanoTime())).toString();
        assertTrue(PathUtils.makePath(path));
        assertTrue(new File(path).delete());
    }

    /**
     * Concurrent first messages on a fresh store all create the spool directory at once;
     * every caller must see success, not only the thread that won the race.
     */
    @Test
    void makePathConcurrently() throws Exception {
        String path = tempDir.resolve("a").resolve("b").resolve("tmp").toString();
        int threads = 16;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return PathUtils.makePath(path);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void makePathFailsOnFile() throws IOException {
        Path file = tempDir.resolve("file");
        java.nio.file.Files.writeString(file, "x");
        assertFalse(PathUtils.makePath(file.toString()));
    }

    @Test
    void isDirectory() {
        assertTrue(PathUtils.isDirectory("src/test/resources/"));
    }

    @Test
    void isNotDirectory() {
        assertFalse(PathUtils.isFile("src/test/resources/not.dir/"));
    }

    @Test
    void readFile() throws IOException {
        String payload = PathUtils.readFile("src/test/resources/cfg/properties.json5", Charset.defaultCharset());
        assertEquals(123, payload.charAt(0));
        assertEquals(125, payload.charAt(payload.length() - 1));
    }

    @ParameterizedTest
    @CsvSource({
        "robin.eml,robin.eml",
        "../../etc/passwd,passwd",
        "..\\..\\windows\\evil.exe,evil.exe",
        "/etc/passwd,passwd",
        "C:\\Windows\\System32\\evil.exe,evil.exe",
        "\\\\server\\share\\evil.exe,evil.exe",
        "foo/../../bar.eml,bar.eml"
    })
    void safeFileNameStripsDirectoryComponents(String value, String expected) {
        assertEquals(expected, PathUtils.safeFileName(value));
    }

    @Test
    void safeFileNameTrimsWhitespace() {
        assertEquals("robin.eml", PathUtils.safeFileName("  robin.eml  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "", "   ", "/", "../", "..\\"})
    void safeFileNameRejectsUnsafeValues(String value) {
        assertNull(PathUtils.safeFileName(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void safeFileNameRejectsNullAndEmpty(String value) {
        assertNull(PathUtils.safeFileName(value));
    }
}
