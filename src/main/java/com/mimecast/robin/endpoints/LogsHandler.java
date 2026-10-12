package com.mimecast.robin.endpoints;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Handler for searching log files.
 *
 * <p>This class provides functionality to search log files for matching query strings.
 * It dynamically discovers log file paths and date formats from log4j2 configuration.
 */
public class LogsHandler {
    private static final Logger log = LogManager.getLogger(LogsHandler.class);
    private final LoggerContext context;

    public LogsHandler() {
        this((LoggerContext) LogManager.getContext(false));
    }

    LogsHandler(LoggerContext context) {
        this.context = context;
    }

    /**
     * Searches current and previous log files for lines matching the query string.
     *
     * @param query The search query string.
     * @return Search results containing matching log lines.
     * @throws LogsSearchException if unable to determine log file configuration or search fails.
     */
    public String searchLogs(String query) throws LogsSearchException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }

        log.info("Searching logs for query: '{}'", query);
        RollingFileAppender appender = getRollingFileAppender();
        if (appender == null) {
            throw new LogsSearchException("Could not determine log file location from log4j2 configuration");
        }
        return searchLogs(query, appender.getFilePattern(), appender.getFileName());
    }

    String searchLogs(String query, String logFilePattern, String currentFile) throws LogsSearchException {
        StringBuilder results = new StringBuilder();

        if (logFilePattern == null) {
            log.error("Could not determine log file pattern from log4j2 configuration");
            throw new LogsSearchException("Could not determine log file location from log4j2 configuration");
        }

        // Extract date format from the log file pattern
        String dateFormat = extractDateFormatFromPattern(logFilePattern);
        if (dateFormat == null) {
            log.error("Could not extract date format from log file pattern: {}", logFilePattern);
            throw new LogsSearchException("Could not extract date format from log file pattern");
        }

        // Get current date and yesterday's date for log file names
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);

        LinkedHashSet<Path> files = new LinkedHashSet<>();
        if (currentFile != null) {
            files.add(Paths.get(currentFile).toAbsolutePath().normalize());
        }
        String datePatternPlaceholder = "%d{" + dateFormat + "}";
        for (LocalDate date : new LocalDate[]{today, yesterday}) {
            Path datedPattern = Paths.get(logFilePattern.replace(datePatternPlaceholder, date.format(formatter)));
            if (!datedPattern.getFileName().toString().contains("%i")) {
                files.add(datedPattern.toAbsolutePath().normalize());
                continue;
            }
            Path directory = datedPattern.toAbsolutePath().getParent();
            if (!Files.exists(directory)) {
                continue;
            }
            Pattern name = Pattern.compile(Pattern.quote(datedPattern.getFileName().toString())
                    .replace("%i", "\\E[0-9]+\\Q"));
            try (var entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile)
                        .filter(path -> name.matcher(path.getFileName().toString()).matches())
                        .sorted().forEach(files::add);
            } catch (IOException e) {
                throw new LogsSearchException("Could not inspect rolled log files", e);
            }
        }
        int matchCount = 0;
        for (Path file : files) {
            matchCount += searchLogFile(file.toString(), query, results);
        }
        log.debug("Logs search completed: query='{}', matches={}", query, matchCount);
        return results.toString();
    }

    /**
     * Gets the log file pattern from log4j2 configuration by examining appenders.
     *
     * @return The log file pattern (e.g., "/var/log/robin-%d{yyyyMMdd}.log") or null if not found.
     */
    private String getLogFilePatternFromLog4j2() {
        RollingFileAppender appender = getRollingFileAppender();
        return appender != null ? appender.getFilePattern() : null;
    }

    private RollingFileAppender getRollingFileAppender() {
        try {
            Configuration config = context.getConfiguration();
            
            // Iterate through all appenders to find a RollingFileAppender
            for (Map.Entry<String, Appender> entry : config.getAppenders().entrySet()) {
                Appender appender = entry.getValue();
                if (appender instanceof RollingFileAppender) {
                    RollingFileAppender rollingFileAppender = (RollingFileAppender) appender;
                    String filePattern = rollingFileAppender.getFilePattern();
                    if (filePattern != null && !filePattern.isBlank()) {
                        log.debug("Found log file pattern from appender '{}': {}", entry.getKey(), filePattern);
                        return rollingFileAppender;
                    }
                }
            }
            log.warn("No RollingFileAppender found in log4j2 configuration");
        } catch (Exception e) {
            log.error("Error reading log4j2 configuration: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Extracts the date format from a log4j2 file pattern.
     * <p>For example, extracts "yyyyMMdd" from "/var/log/robin-%d{yyyyMMdd}.log"
     *
     * @param filePattern The log file pattern from log4j2 configuration.
     * @return The date format string (e.g., "yyyyMMdd") or null if not found.
     */
    private String extractDateFormatFromPattern(String filePattern) {
        if (filePattern == null || filePattern.isBlank()) {
            return null;
        }
        
        // Look for %d{...} pattern
        int startIndex = filePattern.indexOf("%d{");
        if (startIndex == -1) {
            log.debug("No date pattern found in file pattern: {}", filePattern);
            return null;
        }
        
        int endIndex = filePattern.indexOf("}", startIndex);
        if (endIndex == -1) {
            log.debug("Malformed date pattern in file pattern: {}", filePattern);
            return null;
        }
        
        String dateFormat = filePattern.substring(startIndex + 3, endIndex);
        log.debug("Extracted date format '{}' from pattern '{}'", dateFormat, filePattern);
        return dateFormat;
    }

    /**
     * Searches a log file for lines matching the query string.
     *
     * @param logFilePath Path to the log file.
     * @param query Query string to search for.
     * @param results StringBuilder to append matching lines to.
     * @return Number of matches found.
     */
    private int searchLogFile(String logFilePath, String query, StringBuilder results) throws LogsSearchException {
        Path path = Paths.get(logFilePath);
        if (!Files.exists(path)) {
            log.debug("Log file does not exist: {}", logFilePath);
            return 0;
        }

        int matches = 0;
        try (InputStream file = Files.newInputStream(path);
             InputStream decoded = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file;
             BufferedReader reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(query)) {
                    results.append(line).append("\n");
                    matches++;
                }
            }
            log.debug("Searched log file: {}, found {} matches", logFilePath, matches);
        } catch (IOException e) {
            log.error("Error reading log file {}: {}", logFilePath, e.getMessage());
            throw new LogsSearchException("Could not read log file: " + logFilePath, e);
        }
        return matches;
    }

    /**
     * Exception thrown when log search operations fail.
     */
    public static class LogsSearchException extends Exception {
        public LogsSearchException(String message) {
            super(message);
        }

        public LogsSearchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
