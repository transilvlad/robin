package com.mimecast.robin.scanners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A mock server that simulates ClamAV daemon responses for testing.
 * <p>
 * This server implements ClamAV protocol responses according to the format
 * expected by the xyz.capybara.clamav-client library:
 * - PING: responds with "PONG\0"
 * - VERSION: responds with mock version string followed by null byte
 * - INSTREAM: handles binary data chunks and responds with scan results
 * - SCAN: handles file path scan requests
 * - nZSCAN: handles network socket scanning (zero-copy)
 */
public class ClamAVMockServer {
    private static final Logger log = LogManager.getLogger(ClamAVMockServer.class);

    private static final String EICAR_TEST_SIGNATURE = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    // Simplified mock version string; the library parser rejects slash-delimited extended metadata.
    private volatile String VERSION_RESPONSE = "ClamAV 0.103.9"; // Must end with null terminator when sent.
    private static final String SIMULATED_VIRUS_MARKER = "SIMULATED_VIRUS";

    // Command names used by the ClamAV protocol.
    private static final String CMD_PING = "PING";
    private static final String CMD_VERSION = "VERSION";
    private static final String CMD_INSTREAM = "INSTREAM";
    private static final String CMD_SCAN = "SCAN";
    private static final String CMD_SHUTDOWN = "SHUTDOWN";
    private static final String CMD_NZSCAN = "zINSTREAM"; // Zero-copy INSTREAM.
    private static final String CMD_RELOAD = "RELOAD";
    private static final String CMD_MULTISCAN = "MULTISCAN";
    private static final String CMD_STREAM = "STREAM";
    private static final String CMD_RAWSCAN = "RAWSCAN";
    private static final String CMD_CONTSCAN = "CONTSCAN";
    private static final String CMD_ALLMATCHSCAN = "ALLMATCHSCAN";
    private static final String CMD_IDSESSION = "IDSESSION";
    private static final String CMD_END = "END";

    // Special commands used by the xyz.capybara.clamav-client library.
    private static final String CMD_N_VERSION = "nVERSION";
    private static final String CMD_N_VERSION_COMMANDS = "nVERSIONCOMMANDS";
    private static final String CMD_STATS = "STATS";

    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Create a mock ClamAV server.
     *
     * @param port The port to listen on.
     */
    public ClamAVMockServer(int port) {
        this.port = port;
    }

    /**
     * Optionally override the version string sent back to clients.
     * Useful for testing parser robustness against extended metadata forms.
     *
     * @param version New version string (without trailing null terminator)
     */
    public void setVERSION_RESPONSE(String version) {
        if (version != null && !version.isEmpty()) {
            this.VERSION_RESPONSE = version;
        }
    }

    /**
     * Start the mock server.
     *
     * @return True if server started successfully, false otherwise.
     */
    public boolean start() {
        try {
            serverSocket = new ServerSocket(port);
            running.set(true);
            executor = Executors.newCachedThreadPool();

            executor.submit(() -> {
                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executor.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running.get()) {
                            log.error("Error accepting client connection: {}", e.getMessage());
                        }
                    }
                }
            });

            log.info("ClamAV mock server started on port {}", port);
            return true;
        } catch (IOException e) {
            log.error("Failed to start ClamAV mock server: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Stop the mock server.
     */
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("Error closing server socket: {}", e.getMessage());
        }

        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Executor did not terminate in the specified time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("ClamAV mock server stopped");
    }

    /**
     * Handle a client connection.
     *
     * @param socket The client socket.
     */
    private void handleClient(Socket socket) {
        try (socket) {
            try {
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();

                String commandLine = readCommand(is);

                if (commandLine == null) {
                    log.debug("Null/empty command received, closing connection");
                    return;
                }

                // Normalize: strip leading prefix 'z' or 'n' if remainder matches known command.
                String normalized = normalizeCommand(commandLine);
                log.debug("Received command raw='{}' normalized='{}'", commandLine, normalized);

                if (CMD_PING.equals(normalized)) {
                    handlePing(os);
                } else if (CMD_VERSION.equals(normalized) || CMD_N_VERSION.equals(normalized)) {
                    handleVersion(os);
                } else if (CMD_INSTREAM.equals(normalized) || CMD_NZSCAN.equals(normalized)) {
                    // Choose appropriate handler: if original had 'z' prefix treat as zero-copy.
                    if (commandLine.startsWith("z")) {
                        handleZeroStream(is, os);
                    } else {
                        handleInstream(is, os);
                    }
                } else if (normalized.startsWith(CMD_SCAN + " ")) {
                    handleScan(normalized.substring(CMD_SCAN.length() + 1), os);
                } else if (CMD_STATS.equals(normalized)) {
                    handleStats(os);
                } else if (CMD_SHUTDOWN.equals(normalized) || CMD_RELOAD.equals(normalized) || CMD_MULTISCAN.equals(normalized) || CMD_RAWSCAN.equals(normalized) || CMD_CONTSCAN.equals(normalized) || CMD_ALLMATCHSCAN.equals(normalized)) {
                    os.write("OK\0".getBytes(StandardCharsets.UTF_8));
                } else if (CMD_STREAM.equals(normalized)) {
                    os.write("PORT 0\0".getBytes(StandardCharsets.UTF_8));
                } else if (CMD_IDSESSION.equals(normalized)) {
                    os.write("SESSION 1\0".getBytes(StandardCharsets.UTF_8));
                } else if (CMD_END.equals(normalized)) {
                    os.write("OK\0".getBytes(StandardCharsets.UTF_8));
                } else if (CMD_N_VERSION_COMMANDS.equals(normalized)) {
                    handleVersionCommands(os);
                } else if ("FILDES".equals(normalized)) {
                    os.write("FILDES: OK\0".getBytes(StandardCharsets.UTF_8));
                } else {
                    os.write("UNKNOWN COMMAND\0".getBytes(StandardCharsets.UTF_8));
                }

            } catch (IOException e) {
                log.error("Error handling client: {}", e.getMessage());
            }
        } catch (IOException e) {
            log.error("Error closing client socket: {}", e.getMessage());
        }
    }

    /**
     * Read a command from the input stream until a newline (\n), null byte (0), or carriage return (\r) is encountered.
     * Returns null if end of stream reached with no data.
     */
    private String readCommand(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n' || b == '\r' || b == 0) {
                break;
            }
            baos.write(b);
            // Guard against excessively long commands.
            if (baos.size() > 1024) {
                log.warn("Command length exceeded 1024 bytes; truncating");
                break;
            }
        }
        if (baos.size() == 0) {
            return null;
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    /**
     * Normalize command by stripping leading 'z' or 'n' prefixes used for zero-copy or metadata queries.
     * If prefixed command is followed by arguments (e.g., zSCAN /path), keep arguments.
     */
    private String normalizeCommand(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        // Handle prefixed single-word commands.
        if ((raw.startsWith("z") || raw.startsWith("n")) && raw.length() > 1) {
            String candidate = raw.substring(1);
            // If candidate starts with SCAN + space treat as SCAN + space.
            if (candidate.startsWith(CMD_SCAN + " ")) {
                return CMD_SCAN + candidate.substring(CMD_SCAN.length());
            }
            if (CMD_PING.equals(candidate) || CMD_VERSION.equals(candidate) || CMD_INSTREAM.equals(candidate) || CMD_SCAN.equals(candidate) ||
                    CMD_STREAM.equals(candidate) || CMD_RAWSCAN.equals(candidate) || CMD_CONTSCAN.equals(candidate) || CMD_MULTISCAN.equals(candidate) || CMD_ALLMATCHSCAN.equals(candidate) || CMD_IDSESSION.equals(candidate) || CMD_END.equals(candidate)) {
                return candidate;
            }
            // Special for nVERSIONCOMMANDS.
            if (CMD_N_VERSION_COMMANDS.equals(candidate)) {
                return candidate;
            }
        }
        return raw;
    }

    /**
     * Handle the PING command.
     *
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handlePing(OutputStream os) throws IOException {
        os.write("PONG\0".getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the VERSION command.
     *
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleVersion(OutputStream os) throws IOException {
        String sanitized = VERSION_RESPONSE;
        int slashIdx = sanitized.indexOf('/');
        if (slashIdx > -1) sanitized = sanitized.substring(0, slashIdx);
        os.write((sanitized.trim() + "\0").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the SCAN command for a file.
     *
     * @param filePath File path to "scan"
     * @param os       Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleScan(String filePath, OutputStream os) throws IOException {
        boolean infected = false;
        File f = new File(filePath);
        if (f.isFile()) {
            try (InputStream fis = new FileInputStream(f)) {
                byte[] buf = fis.readNBytes(8192);
                String data = new String(buf, StandardCharsets.UTF_8);
                if (data.contains(EICAR_TEST_SIGNATURE) || data.contains(SIMULATED_VIRUS_MARKER)) infected = true;
            } catch (Exception e) {
                // Fallback to path heuristic if unreadable.
                if (filePath.toLowerCase().contains("malware") || filePath.toLowerCase().contains("eicar"))
                    infected = true;
            }
        }
        String response = infected ? filePath + ": Eicar-Test-Signature FOUND\0" : filePath + ": OK\0";
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the INSTREAM command for streaming data.
     *
     * @param is Input stream to read data chunks
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleInstream(InputStream is, OutputStream os) throws IOException {
        ByteArrayOutputStream contentBuffer = new ByteArrayOutputStream();
        byte[] transferBuffer = new byte[8192];
        while (true) {
            byte[] sizeBytes = new byte[4];
            int readSizeHeader = is.read(sizeBytes);
            if (readSizeHeader != 4) {
                log.debug("INSTREAM: Expected 4 bytes size header, got {} - terminating", readSizeHeader);
                break; // abort
            }
            int chunkSize = ByteBuffer.wrap(sizeBytes).getInt();
            if (chunkSize == 0) {
                log.debug("INSTREAM: Received terminating zero-size chunk");
                break; // End of stream.
            }
            int remaining = chunkSize;
            while (remaining > 0) {
                int toRead = Math.min(remaining, transferBuffer.length);
                int r = is.read(transferBuffer, 0, toRead);
                if (r == -1) {
                    log.debug("INSTREAM: Unexpected EOF inside chunk");
                    remaining = 0;
                    break;
                }
                contentBuffer.write(transferBuffer, 0, r);
                remaining -= r;
            }
        }
        String contentStr = contentBuffer.toString(StandardCharsets.UTF_8);
        String response = (contentStr.contains(EICAR_TEST_SIGNATURE) || contentStr.contains(SIMULATED_VIRUS_MARKER)) ?
                "stream: Eicar-Test-Signature FOUND\0" : "stream: OK\0";
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the zINSTREAM command for zero-copy streaming.
     *
     * @param is Input stream to read data
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleZeroStream(InputStream is, OutputStream os) throws IOException {
        // Similar to INSTREAM but with different protocol details.
        ByteArrayOutputStream contentBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;

        // Just read all available data (simplified for testing).
        while ((bytesRead = is.read(buffer)) != -1) {
            contentBuffer.write(buffer, 0, bytesRead);
            // If we detect the end marker, stop.
            if (contentBuffer.size() >= 4) {
                byte[] lastFour = contentBuffer.toByteArray();
                int len = lastFour.length;
                if (lastFour[len - 4] == 0 && lastFour[len - 3] == 0 &&
                        lastFour[len - 2] == 0 && lastFour[len - 1] == 0) {
                    break;
                }
            }
        }

        String contentStr = contentBuffer.toString(StandardCharsets.UTF_8);

        String response = (contentStr.contains(EICAR_TEST_SIGNATURE) || contentStr.contains(SIMULATED_VIRUS_MARKER)) ?
                "stream: Eicar-Test-Signature FOUND\0" : "stream: OK\0";
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the nVERSIONCOMMANDS command, which returns the list of supported commands.
     *
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleVersionCommands(OutputStream os) throws IOException {
        os.write(("| COMMANDS:\n" +
                "0: PING \n" +
                "1: VERSION \n" +
                "2: RELOAD \n" +
                "3: SHUTDOWN \n" +
                "4: SCAN \n" +
                "5: CONTSCAN \n" +
                "6: MULTISCAN \n" +
                "7: ALLMATCHSCAN \n" +
                "8: INSTREAM \n" +
                "9: FILDES \n" +
                "10: STATS \n" +
                "11: IDSESSION \n" +
                "12: END \n" +
                "\0").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Handle the STATS command, which returns server statistics.
     *
     * @param os Output stream to write response
     * @throws IOException If an I/O error occurs
     */
    private void handleStats(OutputStream os) throws IOException {
        String statsResponse = "POOLS: 1\n\nSTATS 0\nSTATE: VALID PRIMARY\nTHREADS: live 1  idle 0 max 12 idle-timeout 30\nQUEUE: 0 items\n\tSTATS 0.000 0\n\nEND\0";
        os.write(statsResponse.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
