package com.mimecast.robin.endpoints;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
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
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LogsHandler.
 * <p>Each test uses an isolated Log4j2 context and temporary log files.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LogsHandlerTest {

    private Path tempDir;
    private Path todayLogFile;
    private Path yesterdayLogFile;
    private LoggerContext context;

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
        Path configuration = tempDir.resolve("log4j2.xml");
        Files.writeString(configuration, """
                <Configuration>
                    <Appenders>
                        <RollingFile name="logs" fileName="%s" filePattern="%s">
                            <PatternLayout/>
                            <Policies><TimeBasedTriggeringPolicy/></Policies>
                        </RollingFile>
                    </Appenders>
                    <Loggers><Root level="error"><AppenderRef ref="logs"/></Root></Loggers>
                </Configuration>
                """.formatted(tempDir.resolve("active.log"), tempDir.resolve("robin-%d{yyyyMMdd}.log")));
        context = new LoggerContext("logs-handler-test");
        context.start(ConfigurationFactory.getInstance().getConfiguration(context, "logs", configuration.toUri()));
    }

    @AfterEach
    void tearDown() throws IOException {
        context.stop();
        Files.deleteIfExists(tempDir.resolve("active.log"));
        Files.deleteIfExists(tempDir.resolve("log4j2.xml"));
        // Clean up test log files
        Files.deleteIfExists(todayLogFile);
        Files.deleteIfExists(yesterdayLogFile);
        Files.deleteIfExists(tempDir);
    }

    /**
     * Tests that LogsHandler can search logs successfully.
     */
    @Test
    void testSearchLogs() throws LogsHandler.LogsSearchException {
        LogsHandler handler = new LogsHandler(context);
        assertEquals(3, handler.searchLogs("INFO").lines().count());
    }

    /**
     * Tests that LogsHandler throws exception for null query.
     */
    @Test
    void testSearchLogsNullQuery() {
        LogsHandler handler = new LogsHandler(context);
        
        assertThrows(IllegalArgumentException.class, () -> {
            handler.searchLogs(null);
        });
    }

    /**
     * Tests that LogsHandler throws exception for blank query.
     */
    @Test
    void testSearchLogsBlankQuery() {
        LogsHandler handler = new LogsHandler(context);
        
        assertThrows(IllegalArgumentException.class, () -> {
            handler.searchLogs("   ");
        });
    }

    /**
     * Tests that LogsHandler can discover log file pattern from log4j2.
     */
    @Test
    void testGetLogFilePattern() {
        LogsHandler handler = new LogsHandler(context);
        
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
        LogsHandler handler = new LogsHandler(context);
        
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

    @Test
    void searchesLiveAndIndexedCompressedLogsWithoutIncludingOlderArchives() throws Exception {
        Path live = tempDir.resolve("robin.log");
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        String old = LocalDate.now().minusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE);
        Path first = tempDir.resolve("robin-" + today + "-1.log.gz");
        Path second = tempDir.resolve("robin-" + yesterday + "-2.log.gz");
        Path previous = tempDir.resolve("robin-" + old + "-1.log.gz");
        Files.writeString(live, "needle live\n");
        try {
            for (Path path : new Path[]{first, second, previous}) {
                try (var gzip = new GZIPOutputStream(Files.newOutputStream(path))) {
                    gzip.write(("needle " + path.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            String result = new LogsHandler().searchLogs("needle",
                    tempDir.resolve("robin-%d{yyyyMMdd}-%i.log.gz").toString(), live.toString());
            assertEquals(3, result.lines().count());
            assertTrue(result.contains("needle live"));
            assertTrue(result.contains(first.getFileName().toString()));
            assertTrue(result.contains(second.getFileName().toString()));
            assertFalse(result.contains(previous.getFileName().toString()));
        } finally {
            for (Path path : new Path[]{live, first, second, previous}) Files.deleteIfExists(path);
        }
    }

    @Test
    void directDatePatternDoesNotDuplicateTheLiveFile() throws Exception {
        String pattern = tempDir.resolve("robin-%d{yyyyMMdd}.log").toString();
        String result = new LogsHandler().searchLogs("ERROR", pattern, todayLogFile.toString());
        assertEquals(2, result.lines().count());
    }

    @Test
    void corruptCompressedLogReportsSearchFailure() throws Exception {
        Path corrupt = tempDir.resolve("robin-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-1.log.gz");
        Files.writeString(corrupt, "not gzip");
        try {
            assertThrows(LogsHandler.LogsSearchException.class, () -> new LogsHandler().searchLogs("ERROR",
                    tempDir.resolve("robin-%d{yyyyMMdd}-%i.log.gz").toString(), todayLogFile.toString()));
        } finally {
            Files.deleteIfExists(corrupt);
        }
    }
}
