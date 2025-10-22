package com.mimecast.robin.sasl;

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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DovecotSaslAuthNative is a UNIX domain socket client for authenticating users against
 * a Dovecot SASL authentication service using native Java socket support (JDK 16+).
 * <p>
 * This class provides two main authentication operations:
 * <ul>
 *     <li><b>validate()</b> - Check if a user exists in the Dovecot authentication database</li>
 *     <li><b>authenticate()</b> - Verify user credentials using the PLAIN SASL mechanism</li>
 * </ul>
 * <p>
 * The implementation uses UNIX domain sockets (AF_UNIX) which offer several advantages over
 * TCP sockets for local inter-process communication: no network stack overhead, better security
 * (file system permissions), and lower latency. Connection is established during construction
 * and maintained throughout the lifetime of the instance.
 * <p>
 * Thread Safety: This class is NOT thread-safe. Each thread requiring Dovecot authentication
 * should create its own DovecotSaslAuthNative instance. The requestIdCounter ensures unique
 * request IDs within a single instance, but socket I/O is not synchronized.
 * <p>
 * Requirements:
 * <ul>
 *     <li>Java 16 or higher (for native UNIX domain socket support)</li>
 *     <li>Access to a Dovecot authentication socket (requires file system permissions)</li>
 *     <li>Dovecot authentication service must be running</li>
 *     <li>Unix/Linux operating system</li>
 * </ul>
 * <p>
 * Usage Example:
 * <pre>
 * Path dovecotSocket = Paths.get("/var/run/dovecot/auth-userdb");
 * try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(dovecotSocket)) {
 *     if (auth.validate("username", "smtp")) {
 *         boolean authenticated = auth.authenticate(
 *             "PLAIN",
 *             true,
 *             "username",
 *             "password",
 *             "smtp",
 *             "192.168.1.10",
 *             "203.0.113.50"
 *         );
 *     }
 * } catch (IOException e) {
 *     e.printStackTrace();
 * }
 * </pre>
 *
 * @see java.net.UnixDomainSocketAddress
 * @see java.nio.channels.SocketChannel
 * @see java.util.concurrent.atomic.AtomicLong
 */
public class DovecotSaslAuthNative implements AutoCloseable {
    protected static final Logger log = LogManager.getLogger(DovecotSaslAuthNative.class);

    /** The file system path to the Dovecot authentication socket. */
    private final Path socketPath;

    /** Counter for generating unique request IDs per authentication request. */
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    /** The underlying UNIX domain socket channel. */
    protected SocketChannel channel;

    /** Output stream for writing requests to the Dovecot socket. */
    protected OutputStream outputStream;

    /** Input stream for reading responses from the Dovecot socket. */
    protected InputStream inputStream;

    /**
     * Constructs a new DovecotSaslAuthNative client and establishes connection to Dovecot.
     * <p>
     * Initializes a UNIX domain socket connection to the specified Dovecot authentication
     * socket path. The socket is opened immediately during construction, and the connection
     * is maintained for the lifetime of this instance. If socket initialization fails,
     * the instance is created but in a non-functional state (streams will be null).
     * <p>
     * Call validate() or authenticate() to check if the connection was successful;
     * both methods verify that streams are initialized before attempting communication.
     * <p>
     * This instance should be used with try-with-resources to ensure proper cleanup:
     * <pre>
     * try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(socketPath)) {
     *     // use auth
     * }
     * </pre>
     *
     * @param socketPath A Path instance pointing to the Dovecot auth socket file.
     *                   Typically, /var/run/dovecot/auth-userdb on standard installations.
     *                   Must have appropriate file system permissions for reading and writing.
     * @see #initSocket()
     * @see #close()
     */
    public DovecotSaslAuthNative(Path socketPath) {
        this.socketPath = socketPath;
        initSocket();
    }

    /**
     * Initializes the UNIX domain socket connection to Dovecot.
     * Called automatically during construction. Logs all operations at DEBUG level.
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
     * Validates if a user exists in the Dovecot authentication database.
     * <p>
     * This method performs a quick existence check without credential verification.
     * It's useful for determining if a username is valid before attempting authentication,
     * or for user enumeration in directory-like operations.
     * <p>
     * Uses automatic PID and request ID generation. For more control over these parameters,
     * use the overloaded method that accepts explicit processId and requestId.
     * <p>
     * The Dovecot protocol exchange:
     * <ol>
     *     <li>Sends VERSION command (protocol version 1.1)</li>
     *     <li>Sends USER command with username and service</li>
     *     <li>Receives response starting with "USER\t{requestId}" for success</li>
     * </ol>
     * <p>
     * Common service names: "smtp", "imap", "pop3", "sieve"
     *
     * @param username The username to validate (e.g., "user@example.com")
     * @param service  The service name indicating the protocol context (e.g., "smtp")
     * @return true if the user exists and Dovecot confirms via response starting with "USER\t{id}",
     *         false if validation fails, user doesn't exist, or socket is uninitialized
     * @throws IOException If an error occurs during socket communication (socket I/O errors, etc.)
     * @see #authenticate(String, boolean, String, String, String, String, String)
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
     * Authenticates a user with provided credentials using PLAIN SASL mechanism.
     * <p>
     * This is a convenience overload that automatically generates the current process ID
     * and a unique request ID. For more control, use the fully parameterized overload
     * that accepts explicit processId and requestId values.
     * <p>
     * The PLAIN mechanism sends credentials in Base64-encoded format: "\0username\0password"
     * and requires the connection to be secured (TLS/SSL) in production deployments.
     * <p>
     * IP addresses provided are used by Dovecot for logging and rate limiting purposes.
     * They represent the server's listening address (localIp) and the original client's
     * address (remoteIp), allowing Dovecot to track authentication sources.
     *
     * @param mechanism Authentication mechanism, typically "PLAIN" for standard username/password auth
     * @param secured   Boolean indicating if the connection from client to server is secured via TLS/SSL.
     *                  Should be true for production to protect credentials in transit.
     * @param username  The username to authenticate (e.g., "user@example.com")
     * @param password  The password for the user
     * @param service   The service name indicating protocol context (e.g., "smtp", "imap")
     * @param localIp   The server's local IP address where this authentication is occurring
     *                  (e.g., "192.168.1.10", "::1" for IPv6)
     * @param remoteIp  The original client's IP address that initiated the connection to the server
     *                  (e.g., "203.0.113.50")
     * @return true if authentication succeeds (response starts with "OK\t{id}"),
     *         false if credentials are invalid, user doesn't exist, or socket is uninitialized
     * @throws IOException If an error occurs during socket communication
     * @see #authenticate(String, boolean, String, String, String, String, String, long, long)
     */
    public boolean authenticate(String mechanism, boolean secured, String username, String password, String service, String localIp, String remoteIp) throws IOException {
        long pid = ProcessHandle.current().pid();
        long requestId = requestIdCounter.getAndIncrement();

        return authenticate(mechanism, secured, username, password, service, localIp, remoteIp, pid, requestId);
    }

    /**
     * Authenticates a user with provided credentials and explicit process/request IDs.
     * <p>
     * This is the fully parameterized authentication method providing complete control over
     * all authentication parameters. Use this when you need to:
     * <ul>
     *     <li>Specify a custom process ID (e.g., for tracking in Dovecot logs)</li>
     *     <li>Use explicit request IDs for correlation and debugging</li>
     *     <li>Implement request queuing or batch authentication</li>
     *     <li>Support custom service types or authentication mechanisms</li>
     * </ul>
     * <p>
     * The PLAIN SASL mechanism encodes credentials as: Base64("\0username\0password")
     * Both validation (USER command) and authentication (AUTH command) use the requestId
     * to correlate requests and responses, ensuring proper matching in Dovecot's response.
     * <p>
     * Protocol exchange:
     * <ol>
     *     <li>Sends VERSION command (protocol version 1.2 for AUTH)</li>
     *     <li>Sends CPID (client process ID) parameter</li>
     *     <li>Sends AUTH command with mechanism, credentials, service, and IP addresses</li>
     *     <li>Receives response: "OK\t{requestId}" for success or "FAIL" for failure</li>
     * </ol>
     *
     * @param protocol  Authentication protocol/mechanism, typically "PLAIN" or "CRAM-MD5"
     * @param secured   Boolean indicating if the connection is secured via TLS/SSL.
     *                  Production deployments should set this to true.
     * @param username  The username attempting authentication (e.g., "user@example.com")
     * @param password  The user's password for credential verification
     * @param service   The service type for which authentication is occurring
     *                  (e.g., "smtp", "imap", "pop3"). Used by Dovecot for service-specific policies.
     * @param localIp   The server's IP address handling this authentication request
     *                  (e.g., "192.168.1.10" for IPv4, "::1" for IPv6 localhost)
     * @param remoteIp  The originating client's IP address for this authentication attempt
     *                  (e.g., "203.0.113.50"). Used for rate limiting and forensics.
     * @param processId The process ID associated with the client application requesting authentication.
     *                  Useful for correlating multiple authentication requests to the same process.
     *                  Can be obtained via: ProcessHandle.current().pid()
     * @param requestId A unique identifier for this specific authentication request.
     *                  Used for matching responses from Dovecot. Should be unique within this instance.
     *                  The requestIdCounter in this class ensures uniqueness automatically.
     * @return true if authentication succeeds and Dovecot returns "OK\t{requestId}",
     *         false if credentials are invalid, user doesn't exist, or socket is uninitialized
     * @throws IOException If an error occurs while communicating with the Dovecot socket
     *                     (socket I/O errors, connection lost, etc.)
     * @see #authenticate(String, boolean, String, String, String, String, String)
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
     * Constructs a user validation request in Dovecot protocol format.
     */
    private String buildUserRequest(String username, String service, long requestId) {
        return "VERSION\t1\t1\n" +
                "USER\t1" + requestId + "\t" + username + "\tservice=" + service + "\n";
    }

    /**
     * Constructs a PLAIN SASL authentication request in Dovecot protocol format.
     * Encodes credentials as Base64("\0username\0password").
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
     * Performs socket communication: read welcome, send request, read response.
     * All communication uses UTF-8 encoding. Responses are trimmed and logged at DEBUG level.
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
     * Logs multi-line protocol messages, splitting by newlines for clarity.
     */
    private void logDebug(String message, String action) {
        log.debug("Socket {}:", action);

        String[] splits = message.trim().split("\n");
        for (String s : splits) {
            log.debug("<< {}", s);
        }
    }

    /**
     * Closes the UNIX domain socket connection and associated streams.
     * Catches and logs any exceptions at ERROR level to ensure cleanup occurs.
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
