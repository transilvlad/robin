package com.mimecast.robin.scanners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * ClamAV antivirus scanner client.
 * <p>
 * This class provides functionality to scan files and streams for viruses
 * using the ClamAV antivirus engine through the capybara clamav-client library.
 */
public class ClamAVClient {
    private static final Logger log = LogManager.getLogger(ClamAVClient.class);

    private final ClamavClient client;
    private Map<String, Collection<String>> viruses;

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
        client = new ClamavClient(host, port);
        log.debug("ClamAV client initialized with {}:{}", host, port);
    }

    /**
     * Ping the ClamAV server to check if it's available.
     *
     * @return True if the server responded to ping, false otherwise.
     */
    public boolean ping() {
        try {
            client.ping();
            log.debug("ClamAV server ping successful");
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
            log.debug("ClamAV server version: {}", version);
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
        log.debug("Scanning file: {}", file.getAbsolutePath());
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
        log.debug("Scanning byte array");
        return client.scan(new ByteArrayInputStream(bytes));
    }

    /**
     * Scan an input stream for viruses.
     *
     * @param inputStream The input stream to scan.
     * @return The scan result object with status and details.
     */
    public ScanResult scanStream(InputStream inputStream) {
        log.debug("Scanning input stream");
        return client.scan(inputStream);
    }

    /**
     * Check if a file contains viruses.
     *
     * @param file The file to scan.
     * @return True if the file is infected, false if it's clean.
     * @throws IOException If the file cannot be read.
     */
    public boolean isInfected(File file) throws IOException {
        ScanResult result = scanFile(file);
        log.debug("File {} scan status: {}", file.getAbsolutePath(), result);
        if (result instanceof ScanResult.VirusFound) {
            viruses = ((ScanResult.VirusFound) result).getFoundViruses();
        }
        return result instanceof ScanResult.VirusFound;
    }

    /**
     * Check if a byte array contains viruses.
     *
     * @param bytes The byte array to scan.
     * @return True if the byte array is infected, false if it's clean.
     */
    public boolean isInfected(byte[] bytes) {
        ScanResult result = scanBytes(bytes);
        log.debug("Byte array scan status: {}", result);
        if (result instanceof ScanResult.VirusFound) {
            viruses = ((ScanResult.VirusFound) result).getFoundViruses();
        }
        return result instanceof ScanResult.VirusFound;
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
