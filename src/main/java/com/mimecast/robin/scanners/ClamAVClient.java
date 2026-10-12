package com.mimecast.robin.scanners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ClamAV antivirus scanner client.
 * <p>
 * This class provides functionality to scan files and streams for viruses
 * using the ClamAV antivirus engine through the capybara clamav-client library.
 */
public class ClamAVClient {
    private static final Logger log = LogManager.getLogger(ClamAVClient.class);

    private final ClamavClient client;
    private final int timeoutMillis;
    private Map<String, Collection<String>> viruses;

    private static final ScheduledThreadPoolExecutor SCAN_TIMEOUTS = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "clamav-scan-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    static {
        SCAN_TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    /**
     * Constructor with default host and port.
     * <p>
     * Uses localhost:3310 which is the default for ClamAV daemon.
     */
    public ClamAVClient() {
        this("localhost", 3310);
    }

    /**
     * Constructor with specific host and port.
     *
     * @param host The ClamAV server host.
     * @param port The ClamAV server port.
     */
    public ClamAVClient(String host, int port) {
        this(host, port, 30);
    }

    /**
     * Creates a client with a bounded scan timeout, including blocked uploads.
     *
     * @param host ClamAV hostname.
     * @param port ClamAV port.
     * @param timeoutSeconds Positive scan timeout in seconds.
     */
    public ClamAVClient(String host, int port, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("ClamAV timeout must be positive");
        }
        timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000);
        client = new ClamavClient(host, port);
        log.trace("ClamAV client initialized with {}:{}", host, port);
    }

    /**
     * Ping the ClamAV server to check if it's available.
     *
     * @return True if the server responded to ping, false otherwise.
     */
    public boolean ping() {
        try {
            client.ping();
            log.trace("ClamAV server ping successful");
            return true;
        } catch (Exception e) {
            log.error("Failed to ping ClamAV server: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the ClamAV server version.
     *
     * @return The server version as a string or empty if unable to retrieve.
     */
    public Optional<String> getVersion() {
        try {
            String version = client.version();
            log.trace("ClamAV server version: {}", version);
            return Optional.of(version);
        } catch (Exception e) {
            log.error("Failed to get ClamAV server version: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Scan a file for viruses.
     *
     * @param file The file to scan.
     * @return The scan result object with status and details.
     * @throws IOException If the file cannot be read.
     */
    public ScanResult scanFile(File file) throws IOException {
        viruses = null;
        log.trace("Scanning file: {}", file.getAbsolutePath());
        try (InputStream is = Files.newInputStream(file.toPath())) {
            return scanStream(is);
        }
    }

    /**
     * Scan a byte array for viruses.
     *
     * @param bytes The byte array to scan.
     * @return The scan result object with status and details.
     */
    public ScanResult scanBytes(byte[] bytes) {
        log.trace("Scanning byte array");
        return scanStream(new ByteArrayInputStream(bytes));
    }

    /**
     * Scan an input stream for viruses.
     *
     * @param inputStream The input stream to scan.
     * @return The scan result object with status and details.
     */
    public ScanResult scanStream(InputStream inputStream) {
        log.trace("Scanning input stream");
        viruses = null;
        // The library's INSTREAM transport has no socket/scan timeout. Keep its
        // result types, but bound both upload and response with a closable socket.
        try (Socket socket = new Socket()) {
            var deadline = SCAN_TIMEOUTS.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.error("Unable to close timed-out ClamAV socket: {}", e.getMessage());
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                socket.connect(client.getServer(), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[8192];
                int count;
                while ((count = inputStream.read(chunk)) != -1) {
                    if (count == 0) {
                        continue;
                    }
                    output.writeInt(count);
                    output.write(chunk, 0, count);
                }
                output.writeInt(0);
                output.flush();

                ByteArrayOutputStream response = new ByteArrayOutputStream();
                InputStream input = socket.getInputStream();
                int value;
                while ((value = input.read()) != 0) {
                    if (value == -1) {
                        throw new IOException("ClamAV closed the connection without a complete scan result");
                    }
                    if (response.size() >= 65536) {
                        throw new IOException("ClamAV scan response exceeds 64 KiB");
                    }
                    response.write(value);
                }
                String result = response.toString(StandardCharsets.UTF_8);
                if ("stream: OK".equals(result)) {
                    return ScanResult.OK.INSTANCE;
                }
                if (result.startsWith("stream: ") && result.endsWith(" FOUND")) {
                    String virus = result.substring("stream: ".length(), result.length() - " FOUND".length());
                    if (!virus.isBlank()) {
                        return new ScanResult.VirusFound(Map.of("stream", List.of(virus)));
                    }
                }
                throw new IOException("ClamAV scan failed: " + result);
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException e) {
            log.error("ClamAV scan failed: {}", e.getMessage());
            throw new UncheckedIOException("ClamAV scan failed", e);
        }
    }

    /**
     * Check if a file contains viruses.
     *
     * @param file The file to scan.
     * @return True if the file is infected, false if it's clean.
     * @throws IOException If the file cannot be read.
     */
    public boolean isInfected(File file) throws IOException {
        return processScanResult(scanFile(file));
    }

    /**
     * Check if a byte array contains viruses.
     *
     * @param bytes The byte array to scan.
     * @return True if the byte array is infected, false if it's clean.
     */
    public boolean isInfected(byte[] bytes) {
        return processScanResult(scanBytes(bytes));
    }

    /**
     * Check if an input stream contains viruses.
     * <p>Streams content to ClamAV without buffering it all in memory.
     *
     * @param inputStream The input stream to scan.
     * @return True if the content is infected, false if it's clean.
     */
    public boolean isInfected(InputStream inputStream) {
        return processScanResult(scanStream(inputStream));
    }

    /**
     * Process a scan result to check for infections and log findings.
     *
     * @param result The scan result to process.
     * @return True if the scan result indicates an infection, false otherwise.
     */
    private boolean processScanResult(ScanResult result) {
        viruses = null;
        if (!(result instanceof ScanResult.OK) && !(result instanceof ScanResult.VirusFound)) {
            throw new IllegalStateException("ClamAV returned an invalid scan result");
        }
        boolean isInfected = result instanceof ScanResult.VirusFound;
        log.debug("ClamAV scan result: {}", isInfected ? "Virus Found" : "Clean");
        if (isInfected) {
            viruses = ((ScanResult.VirusFound) result).getFoundViruses();
            log.trace("ClamAV found viruses: {}", viruses);
        }
        return isInfected;
    }

    /**
     * Get the map of detected viruses after a scan.
     *
     * @return Map of virus names to affected files.
     */
    public Map<String, Collection<String>> getViruses() {
        return viruses;
    }
}
