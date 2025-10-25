package com.mimecast.robin.scanners;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mimecast.robin.smtp.session.EmailDirection;
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
    private static final Double DEFAULT_SPAM_SCORE = 7.0;

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;

    private EmailDirection emailDirection;
    private boolean spfScanEnabled;
    private boolean dkimScanEnabled;
    private boolean dmarcScanEnabled;
    private boolean dkimSigningEnabled;
    private String dkimSigningDomain;
    private String dkimSigningSelector;

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
        // Initialize default values for email scanning options
        this.emailDirection = EmailDirection.INBOUND;
        this.spfScanEnabled = true;
        this.dkimScanEnabled = true;
        this.dmarcScanEnabled = true;
        this.dkimSigningEnabled = false;
        this.dkimSigningDomain = null;
        this.dkimSigningSelector = null;
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
            Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl + SCAN_ENDPOINT)
                    .post(body);

            // Add email direction header
            requestBuilder.addHeader("X-Email-Direction", emailDirection.toString());

            // Add authentication scan options as headers
            requestBuilder.addHeader("X-SPF-Scan", String.valueOf(spfScanEnabled));
            requestBuilder.addHeader("X-DKIM-Scan", String.valueOf(dkimScanEnabled));
            requestBuilder.addHeader("X-DMARC-Scan", String.valueOf(dmarcScanEnabled));

            // Add DKIM signing options if enabled
            if (dkimSigningEnabled) {
                if (dkimSigningDomain != null) {
                    requestBuilder.addHeader("X-DKIM-Sign-Domain", dkimSigningDomain);
                }
                if (dkimSigningSelector != null) {
                    requestBuilder.addHeader("X-DKIM-Sign-Selector", dkimSigningSelector);
                }
            }

            Request request = requestBuilder.build();

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
     * @param content       The content to check (bytes).
     * @param spamThreshold The spam score threshold.
     * @return True if content is marked as spam, false otherwise.
     */
    public boolean isSpam(byte[] content, Double spamThreshold) {
        Map<String, Object> result = scanBytes(content);
        return isSpamResult(result, spamThreshold);
    }

    /**
     * Check if content is detected as spam.
     *
     * @param content The content to check (bytes).
     * @return True if content is marked as spam, false otherwise.
     */
    public boolean isSpam(byte[] content) {
        return isSpam(content, DEFAULT_SPAM_SCORE);
    }

    /**
     * Check if content is detected as spam.
     *
     * @param file          The file to check.
     * @param spamThreshold The spam score threshold.
     * @return True if content is marked as spam, false otherwise.
     * @throws IOException If the file cannot be read.
     */
    public boolean isSpam(File file, Double spamThreshold) throws IOException {
        Map<String, Object> result = scanFile(file);
        return isSpamResult(result, spamThreshold);
    }

    /**
     * Check if content is detected as spam.
     *
     * @param file The file to check.
     * @return True if content is marked as spam, false otherwise.
     * @throws IOException If the file cannot be read.
     */
    public boolean isSpam(File file) throws IOException {
        return isSpam(file, DEFAULT_SPAM_SCORE);
    }

    /**
     * Check if a scan result indicates spam.
     *
     * @param result        The scan result map.
     * @param spamThreshold The spam score threshold.
     * @return True if spam is detected, false otherwise.
     */
    private boolean isSpamResult(Map<String, Object> result, Double spamThreshold) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        // If the "spam" key is present and true, consider it spam.
        if (result.get("spam") instanceof Boolean && (Boolean) result.get("spam") &&
                result.get("score") instanceof Double && (Double) result.get("score") >= spamThreshold) {
            log.info("Content marked as SPAM with score: {} above threshold: {}", result.get("score"), spamThreshold);
            return true;
        }

        return false;
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

    /**
     * Set the email direction (inbound or outbound).
     *
     * @param direction The email direction.
     * @return Self.
     */
    public RspamdClient setEmailDirection(EmailDirection direction) {
        this.emailDirection = direction;
        log.debug("Email direction set to: {}", direction.toString());
        return this;
    }

    /**
     * Set whether SPF scanning is enabled.
     *
     * @param enabled True to enable SPF scanning.
     * @return Self.
     */
    public RspamdClient setSpfScanEnabled(boolean enabled) {
        this.spfScanEnabled = enabled;
        log.debug("SPF scan enabled: {}", enabled);
        return this;
    }

    /**
     * Set whether DKIM scanning is enabled.
     *
     * @param enabled True to enable DKIM scanning.
     * @return Self.
     */
    public RspamdClient setDkimScanEnabled(boolean enabled) {
        this.dkimScanEnabled = enabled;
        log.debug("DKIM scan enabled: {}", enabled);
        return this;
    }

    /**
     * Set whether DMARC scanning is enabled.
     *
     * @param enabled True to enable DMARC scanning.
     * @return Self.
     */
    public RspamdClient setDmarcScanEnabled(boolean enabled) {
        this.dmarcScanEnabled = enabled;
        log.debug("DMARC scan enabled: {}", enabled);
        return this;
    }

    /**
     * Set whether DKIM signing is enabled and configure signing options.
     * <p>
     * IMPORTANT: This method configures which domain and selector to use for DKIM signing.
     * The actual private key must be pre-configured in Rspamd's DKIM keystore.
     * Rspamd looks up the private key using the domain/selector combination provided here.
     * <p>
     * Rspamd DKIM key configuration:
     * - Keys are typically stored in /etc/rspamd/dkim/ directory
     * - Private keys should be in PEM format: /etc/rspamd/dkim/{domain}.{selector}.key
     * - Public keys are published in DNS at: {selector}._domainkey.{domain}
     * - Configure key paths in Rspamd's dkim_signing module configuration
     *
     * @param domain   The domain to sign for (e.g., "example.com"). Must match a configured domain in Rspamd.
     * @param selector The DKIM selector (e.g., "default"). Must match a configured selector in Rspamd.
     * @return Self.
     */
    public RspamdClient setDkimSigningOptions(String domain, String selector) {
        this.dkimSigningEnabled = true;
        this.dkimSigningDomain = domain;
        this.dkimSigningSelector = selector;
        log.debug("DKIM signing enabled for domain: {}, selector: {}", domain, selector);
        return this;
    }
}
