package com.mimecast.robin.scanners;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Rspamd antispam scanner client.
 * <p>
 * This class provides functionality to scan emails for spam and phishing
 * using the Rspamd service through HTTP/REST API.
 */
public class RspamdClient {
    private static final Logger log = LogManager.getLogger(RspamdClient.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 11333;
    private static final String SCHEME = "http";
    private static final String SCAN_ENDPOINT = "/checkv2";
    private static final MediaType APPLICATION_OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private Map<String, Object> lastScanResult;

    /**
     * Constructor with default host and port.
     * <p>
     * Uses localhost:11333 which is the default for Rspamd daemon.
     */
    public RspamdClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * Constructor with specific host and port.
     *
     * @param host The Rspamd server host.
     * @param port The Rspamd server port.
     */
    public RspamdClient(String host, int port) {
        this.baseUrl = String.format("%s://%s:%d", SCHEME, host, port);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
        log.debug("Rspamd client initialized with {}:{}", host, port);
    }

    /**
     * Ping the Rspamd server to check if it's available.
     *
     * @return True if the server responded successfully, false otherwise.
     */
    public boolean ping() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/ping")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean success = response.isSuccessful();
                if (success) {
                    log.debug("Rspamd server ping successful");
                } else {
                    log.error("Rspamd server ping failed with status: {}", response.code());
                }
                return success;
            }
        } catch (Exception e) {
            log.error("Failed to ping Rspamd server: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the Rspamd server version and statistics.
     *
     * @return The server info as a JsonObject or empty if unable to retrieve.
     */
    public Optional<JsonObject> getInfo() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/info")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Failed to get Rspamd server info: HTTP {}", response.code());
                    return Optional.empty();
                }

                String body = response.body() != null ? response.body().string() : "";
                JsonObject info = gson.fromJson(body, JsonObject.class);
                log.debug("Rspamd server info retrieved: {}", info);
                return Optional.of(info);
            }
        } catch (Exception e) {
            log.error("Failed to get Rspamd server info: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Scan a file for.
     *
     * @param file The file to scan.
     * @return The scan result as a Map with detected issues.
     * @throws IOException If the file cannot be read.
     */
    public Map<String, Object> scanFile(File file) throws IOException {
        log.debug("Scanning file: {}", file.getAbsolutePath());
        try (InputStream is = Files.newInputStream(file.toPath())) {
            return scanStream(is);
        }
    }

    /**
     * Scan a byte array.
     *
     * @param bytes The byte array to scan.
     * @return The scan result as a Map with detected issues.
     */
    public Map<String, Object> scanBytes(byte[] bytes) {
        log.debug("Scanning byte array of {} bytes", bytes.length);
        return scanStream(new ByteArrayInputStream(bytes));
    }

    /**
     * Scan an input stream.
     *
     * @param inputStream The input stream to scan.
     * @return The scan result as a Map with detected issues.
     */
    public Map<String, Object> scanStream(InputStream inputStream) {
        try {
            byte[] content = inputStream.readAllBytes();
            log.debug("Scanning input stream with {} bytes", content.length);

            RequestBody body = RequestBody.create(content, APPLICATION_OCTET_STREAM);
            Request request = new Request.Builder()
                    .url(baseUrl + SCAN_ENDPOINT)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Rspamd scan failed with status: {}", response.code());
                    return Collections.emptyMap();
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                @SuppressWarnings("unchecked")
                Map<String, Object> result = gson.fromJson(responseBody, Map.class);
                this.lastScanResult = result;
                log.debug("Scan result: {}", result);
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to scan stream: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Check if content is detected as spam.
     *
     * @param content The content to check (bytes).
     * @return True if content is marked as spam, false otherwise.
     */
    public boolean isSpam(byte[] content) {
        Map<String, Object> result = scanBytes(content);
        return isSpamResult(result);
    }

    /**
     * Check if content is detected as spam.
     *
     * @param file The file to check.
     * @return True if content is marked as spam, false otherwise.
     * @throws IOException If the file cannot be read.
     */
    public boolean isSpam(File file) throws IOException {
        Map<String, Object> result = scanFile(file);
        return isSpamResult(result);
    }

    /**
     * Check if a scan result indicates spam.
     *
     * @param result The scan result map.
     * @return True if spam is detected, false otherwise.
     */
    private boolean isSpamResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        Object spamObj = result.get("spam");
        return spamObj instanceof Boolean && (Boolean) spamObj;
    }

    /**
     * Get the spam score from the last scan result.
     *
     * @return The spam score or 0.0 if no scan has been performed.
     */
    public double getScore() {
        if (lastScanResult == null) {
            return 0.0;
        }
        Object score = lastScanResult.get("score");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return 0.0;
    }

    /**
     * Get the detected symbols (rules that matched) from the last scan.
     *
     * @return Map of symbol names to their scores, or empty map if no scan performed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSymbols() {
        if (lastScanResult == null) {
            return Collections.emptyMap();
        }
        Object symbols = lastScanResult.get("symbols");
        if (symbols instanceof Map) {
            return (Map<String, Object>) symbols;
        }
        return Collections.emptyMap();
    }

    /**
     * Get the last complete scan result.
     *
     * @return Map representing the last scan result.
     */
    public Map<String, Object> getLastScanResult() {
        return lastScanResult != null ? lastScanResult : Collections.emptyMap();
    }
}
