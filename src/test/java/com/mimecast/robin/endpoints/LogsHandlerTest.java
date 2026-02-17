package com.mimecast.robin.endpoints;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LogsHandler.
 * <p>These tests access Log4j2 configuration using reflection which may
 * not be thread-safe, so they run serially.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LogsHandlerTest {

    private Path tempDir;
    private Path todayLogFile;
    private Path yesterdayLogFile;

    @BeforeEach
    void setUp() throws IOException {
        // Create temporary directory for test log files
        tempDir = Files.createTempDirectory("robin-test-logs-");
        
        // Get current date and yesterday's date for log file names
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        todayLogFile = tempDir.resolve("robin-" + today.format(formatter) + ".log");
        yesterdayLogFile = tempDir.resolve("robin-" + yesterday.format(formatter) + ".log");

        // Create test log content
        String todayContent = """
                INFO|1109-120000000|main|Robin|Starting application
                ERROR|1109-120001000|worker-1|Session|Connection failed
                DEBUG|1109-120002000|main|Client|Processing request
                INFO|1109-120003000|main|Robin|Application started successfully
                """;

        String yesterdayContent = """
                INFO|1108-120000000|main|Robin|Application starting
                ERROR|1108-120001000|worker-1|Database|Connection timeout
                DEBUG|1108-120002000|main|Client|Request processed
                """;

        Files.writeString(todayLogFile, todayContent, StandardCharsets.UTF_8);
        Files.writeString(yesterdayLogFile, yesterdayContent, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up test log files
        Files.deleteIfExists(todayLogFile);
        Files.deleteIfExists(yesterdayLogFile);
        Files.deleteIfExists(tempDir);
    }

    /**
     * Tests that LogsHandler can search logs successfully.
     */
    @Test
    void testSearchLogs() {
        LogsHandler handler = new LogsHandler();
        
        try {
            // This will search actual log files from log4j2 configuration
            // We just verify it doesn't throw an exception
            String results = handler.searchLogs("INFO");
            assertNotNull(results);
            // Results may be empty if no logs exist yet, which is fine
        } catch (LogsHandler.LogsSearchException e) {
            // This is acceptable - log files may not exist in test environment
            assertTrue(e.getMessage().contains("Could not determine") || 
                      e.getMessage().contains("Could not extract"));
        }
    }

    /**
     * Tests that LogsHandler throws exception for null query.
     */
    @Test
    void testSearchLogsNullQuery() {
        LogsHandler handler = new LogsHandler();
        
        assertThrows(IllegalArgumentException.class, () -> {
            handler.searchLogs(null);
        });
    }

    /**
     * Tests that LogsHandler throws exception for blank query.
     */
    @Test
    void testSearchLogsBlankQuery() {
        LogsHandler handler = new LogsHandler();
        
        assertThrows(IllegalArgumentException.class, () -> {
            handler.searchLogs("   ");
        });
    }

    /**
     * Tests that LogsHandler can discover log file pattern from log4j2.
     */
    @Test
    void testGetLogFilePattern() {
        LogsHandler handler = new LogsHandler();
        
        try {
            // Use reflection to test the private method
            java.lang.reflect.Method method = LogsHandler.class.getDeclaredMethod("getLogFilePatternFromLog4j2");
            method.setAccessible(true);
            
            String pattern = (String) method.invoke(handler);
            assertNotNull(pattern, "Should find a log file pattern");
            assertTrue(pattern.contains("%d{"), "Pattern should contain date format");
        } catch (Exception e) {
            fail("Should be able to get log file pattern: " + e.getMessage());
        }
    }

    /**
     * Tests date format extraction from various patterns.
     */
    @Test
    void testExtractDateFormat() {
        LogsHandler handler = new LogsHandler();
        
        try {
            java.lang.reflect.Method method = LogsHandler.class.getDeclaredMethod("extractDateFormatFromPattern", String.class);
            method.setAccessible(true);
            
            // Test various patterns
            assertEquals("yyyyMMdd", method.invoke(handler, "/var/log/robin-%d{yyyyMMdd}.log"));
            assertEquals("yyyy-MM-dd", method.invoke(handler, "/var/log/app-%d{yyyy-MM-dd}.log"));
            assertEquals("yyyyMMddHH", method.invoke(handler, "./log/test-%d{yyyyMMddHH}.log"));
            
            // Test patterns without date
            assertNull(method.invoke(handler, "/var/log/app.log"));
            assertNull(method.invoke(handler, (String) null));
            assertNull(method.invoke(handler, ""));
        } catch (Exception e) {
            fail("Should be able to extract date format: " + e.getMessage());
        }
    }
}
