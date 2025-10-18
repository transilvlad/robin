package com.mimecast.robin.sasl;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
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
public class DovecotSaslAuthNative implements AutoCloseable {
    protected static final Logger log = LogManager.getLogger(DovecotSaslAuthNative.class);

    // Default path to the Dovecot authentication socket.
    private static final String DEFAULT_DOVECOT_AUTH_SOCKET_PATH = Config.getServer().getDovecot().getStringProperty("authSocket");

    // The path to the Dovecot authentication socket.
    private final Path socketPath;

    // A simple counter to ensure unique request IDs within this client instance.
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    // The socket and its streams.
    protected SocketChannel channel;
    protected OutputStream outputStream;
    protected InputStream inputStream;

    /**
     * Constructs a new DovecotSaslAuthNative client.
     */
    public DovecotSaslAuthNative() {
        this(Paths.get(DEFAULT_DOVECOT_AUTH_SOCKET_PATH));
    }

    /**
     * Constructs a new DovecotSaslAuthNative client with the given socket path.
     *
     * @param socketPath A path instance to the Dovecot auth socket.
     */
    public DovecotSaslAuthNative(Path socketPath) {
        this.socketPath = socketPath;
        initSocket();
    }

    /**
     * Initializes the UNIX domain socket connection.
     */
    @SuppressWarnings("resource")
    void initSocket() {
        log.debug("Initializing unix socket at {}", socketPath);
        try {
            channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath));
            log.debug("Getting streams");
            outputStream = Channels.newOutputStream(channel);
            inputStream = Channels.newInputStream(channel);
            log.debug("Streams ready");
        } catch (Throwable e) {
            log.error("Failed to initialize unix socket at {}: {}", socketPath, e);
        }
    }

    /**
     * Overloaded authenticate method that uses the instance's PID and a unique request ID.
     *
     * @param username The username to authenticate.
     * @param service  The service name (e.g., "smtp").
     * @return true if validation is successful, false otherwise.
     * @throws IOException if there's an error communicating with the socket.
     */
    public boolean validate(String username, String service) throws IOException {
        if (outputStream == null || inputStream == null) {
            log.error("Socket is not initialized. Cannot perform validation.");
            return false;
        }

        // Build the authentication request.
        long requestId = requestIdCounter.getAndIncrement();
        String request = buildUserRequest(username, service, requestId);

        // A successful response starts with "USER" and should match the request ID.
        boolean validation = socketExchange(request)
                // Check if validation succeeded.
                .startsWith("USER\t" + requestId);

        log.debug("Validation {} for user: {}", validation ? "succeeded" : "failed", username);
        return validation;
    }

    /**
     * Overloaded authenticate method that uses the instance's PID and a unique request ID.
     *
     * @param mechanism Authentication mechanism (e.g., "PLAIN").
     * @param secured   Remote connection secured?
     * @param username  The username to authenticate.
     * @param password  The password for the user.
     * @param service   The service name (e.g., "smtp").
     * @param localIp   The IP address of the connecting server.
     * @param remoteIp  The IP address of the original connecting client to the server.
     * @return true if authentication is successful, false otherwise.
     * @throws IOException if there's an error communicating with the socket.
     */
    public boolean authenticate(String mechanism, boolean secured, String username, String password, String service, String localIp, String remoteIp) throws IOException {
        long pid = ProcessHandle.current().pid();
        long requestId = requestIdCounter.getAndIncrement();

        return authenticate(mechanism, secured, username, password, service, localIp, remoteIp, pid, requestId);
    }

    /**
     * Performs a PLAIN SASL authentication check with a specific process and request ID.
     *
     * @param protocol  Authentication protocol (e.g., "PLAIN").
     * @param secured   Remote connection secured?
     * @param password  Password for the user.
     * @param service   Service name (e.g., "smtp").
     * @param localIp   IP address of the connecting server.
     * @param remoteIp  IP address of the original connecting client to the server.
     * @param processId Process ID of the client application.
     * @param requestId A unique ID for this specific authentication request.
     * @return true if authentication is successful, false otherwise.
     * @throws IOException if there's an error communicating with the socket.
     */
    public boolean authenticate(String protocol, boolean secured, String username, String password, String service, String localIp, String remoteIp, long processId, long requestId) throws IOException {
        if (outputStream == null || inputStream == null) {
            log.error("Socket is not initialized. Cannot perform authentication.");
            return false;
        }

        // Build the authentication request.
        String request = buildAuthRequest(protocol, secured, username, password, service, localIp, remoteIp, processId, requestId);

        // A successful response starts with "OK" and should match the request ID.
        boolean authentication = socketExchange(request)
                // Check if authentication succeeded.
                .startsWith("OK\t" + requestId);

        log.debug("Authentication {} for user: {}", authentication ? "succeeded" : "failed", username);
        return authentication;
    }

    /**
     * Constructs the validation request string.
     *
     * @param username  The username to validate.
     * @param service   The service name (e.g., "smtp").
     * @param requestId A unique ID for this specific authentication request.
     * @return The constructed request string.
     */
    private String buildUserRequest(String username, String service, long requestId) {
        return "VERSION\t1\t1\n" +
                "USER\t1" + requestId + "\t" + username + "\tservice=" + service + "\n";
    }

    /**
     * Constructs the authentication request string with specific IDs.
     *
     * @param protocol  Authentication protocol (e.g., "PLAIN").
     * @param secured   Remote connection secured?
     * @param username  The username to authenticate.
     * @param password  The password for the user.
     * @param service   The service name (e.g., "smtp").
     * @param localIp   The IP address of the connecting server.
     * @param remoteIp  The IP address of the original connecting client to the server.
     * @param processId Process ID of the client application.
     * @param requestId A unique ID for this specific authentication request.
     * @return The constructed request string.
     */
    private String buildAuthRequest(String protocol, boolean secured, String username, String password, String service, String localIp, String remoteIp, long processId, long requestId) {
        String base64 = Base64.getEncoder().encodeToString(("\0" + username + "\0" + password).getBytes(StandardCharsets.UTF_8));

        return "VERSION\t1\t2\n" +
                "CPID=" + processId + "\n" +
                "AUTH\t" + requestId + "\t" +
                protocol + "\t" +
                "service=" + service + "\t" +
                "lip=" + localIp + "\t" +
                "rip=" + remoteIp + "\t" +
                (secured ? "secured\t" : "") +
                "resp=" + base64 + "\n";
    }

    /**
     * Socket exchange helper method to sends the request and receives a response.
     *
     * @param request The request string to send.
     * @return The response string from the socket.
     * @throws IOException if there's an error communicating with the socket.
     */
    private String socketExchange(String request) throws IOException {
        String response = "";

        // Reading welcome.
        byte[] buffer = new byte[1024];
        if (inputStream.read(buffer) > 0) {
            response = new String(buffer, StandardCharsets.UTF_8).trim();
            logDebug(response, "welcome");
        }

        // Sending request.
        logDebug(request, "request");
        outputStream.write(request.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        // Reading response.
        buffer = new byte[1024];
        if (inputStream.read(buffer) > 0) {
            response = new String(buffer, StandardCharsets.UTF_8).trim();
            logDebug(response, "response");
        }

        return response;
    }

    /**
     * Logs debug messages line by line.
     *
     * @param message The message to log.
     * @param action  The action being logged (e.g., "request", "response").
     */
    private void logDebug(String message, String action) {
        log.debug("Socket {}:", action);

        String[] splits = message.trim().split("\n");
        for (String s : splits) {
            log.debug("<< {}", s);
        }
    }

    /**
     * Closes the socket and associated streams.
     */
    @Override
    public void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
            inputStream.close();
            outputStream.close();
        } catch (Exception e) {
            log.error("Error closing socket: {}", e.getMessage());
        }
    }
}
