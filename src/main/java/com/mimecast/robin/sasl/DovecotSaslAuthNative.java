package com.mimecast.robin.sasl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A client to authenticate against the Dovecot SASL authentication socket
 * using native Java UNIX Domain Socket support (JDK 16+).
 */
public class DovecotSaslAuthNative {
    private static final Logger log = LogManager.getLogger(DovecotSaslAuthNative.class);

    // Default path to the Dovecot authentication socket.
    private static final String DEFAULT_DOVECOT_SOCKET_PATH = "/var/spool/postfix/private/auth";

    // The path to the Dovecot authentication socket.
    private final Path socketPath;

    // A simple counter to ensure unique request IDs within this client instance.
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    /**
     * Constructs a new DovecotSaslAuthNative client.
     */
    public DovecotSaslAuthNative() {
        this.socketPath = Paths.get(DEFAULT_DOVECOT_SOCKET_PATH);
    }

    /**
     * Constructs a new DovecotSaslAuthNative client with the given socket path.
     *
     * @param socketPath The filesystem path to the Dovecot auth socket.
     */
    public DovecotSaslAuthNative(String socketPath) {
        this.socketPath = Paths.get(socketPath);
    }

    /**
     * Overloaded authenticate method that uses the instance's PID and a unique request ID.
     *
     * @param username The username to authenticate.
     * @param password The password for the user.
     * @param service  The service name (e.g., "smtp").
     * @param localIp   The IP address of the connecting server.
     * @param remoteIp  The IP address of the original connecting client to the server.
     */
    public boolean authenticate(String username, String password, String service, String localIp, String remoteIp) throws IOException {
        long pid = ProcessHandle.current().pid();
        long requestId = requestIdCounter.getAndIncrement();

        return authenticate(username, password, service, localIp, remoteIp, pid, requestId);
    }

    /**
     * Performs a PLAIN SASL authentication check with a specific process and request ID.
     *
     * @param username  The username to authenticate.
     * @param password  The password for the user.
     * @param service   The service name (e.g., "smtp").
     * @param localIp   The IP address of the connecting server.
     * @param remoteIp  The IP address of the original connecting client to the server.
     * @param processId The process ID of the client application.
     * @param requestId A unique ID for this specific authentication request.
     * @return true if authentication is successful, false otherwise.
     * @throws IOException if there's an error communicating with the socket.
     */
    public boolean authenticate(String username, String password, String service, String localIp, String remoteIp, long processId, long requestId) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            if (channel.connect(address)) {
                try (Socket socket = channel.socket();
                     OutputStream out = socket.getOutputStream();
                     InputStream in = socket.getInputStream()) {

                    String request = buildAuthRequest(username, password, service, localIp, remoteIp, processId, requestId);

                    log.info("Sending Request (PID={}}, ReqID={}):---{}---",
                            processId, requestId, request.trim().replaceAll("\n", "\\"));

                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    byte[] buffer = new byte[1024];
                    int bytesRead = in.read(buffer);

                    if (bytesRead > 0) {
                        String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8).trim();
                        log.info("Received Response: {}", response);
                        // A successful response starts with "OK" and should match the request ID.
                        return response.startsWith("OK\t" + requestId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Authentication exception: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Constructs the authentication request string with specific IDs.
     */
    private String buildAuthRequest(String username, String password, String service, String localIp, String remoteIp, long processId, long requestId) {
        String base64 = Base64.getEncoder().encodeToString(( "\0" + username + "\0" + password).getBytes(StandardCharsets.UTF_8));

        String sb = "AUTH\t" + requestId + "\tPLAIN\t" +
                "service=" + service + "\t" +
                "nologin\t" +
                "lip=" + localIp + "\t" +
                "rip=" + remoteIp + "\t" +
                "resp=" + base64 + "\n";

        return "CPID=" + processId + "\tVERSION=1\t1\n" + sb;
    }
}