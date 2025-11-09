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
import java.util.concurrent.atomic.AtomicLong;

/**
 * DovecotUserLookupNative is a UNIX domain socket client for performing user existence lookups
 * against a Dovecot authentication user database (auth-userdb) service using native Java UNIX
 * domain sockets.
 * <p>
 * This class provides the main user lookup operation:
 * <ul>
 *     <li><b>validate()</b> - Check if a given username exists for a particular service</li>
 * </ul>
 * <p>
 * The implementation communicates with Dovecot's auth-userdb socket using the lightweight
 * Dovecot auth client protocol. It sends a USER query and inspects the response to determine
 * whether the user exists. Unlike full authentication (performed by {@link DovecotSaslAuthNative}),
 * this lookup does not verify passwords; it only confirms presence and returns basic metadata
 * (which can be extended in future if needed).
 * <p>
 * Protocol sequence (Dovecot auth client protocol v1.0):
 * <ol>
 *     <li>Client sends: <pre>VERSION\t1\t0</pre></li>
 *     <li>Client sends: <pre>USER\t&lt;id&gt;\t&lt;username&gt;\tservice=&lt;service&gt;</pre></li>
 *     <li>Server responds success: <pre>USER\t&lt;id&gt; ...</pre></li>
 *     <li>Server responds failure: <pre>NOTFOUND\t&lt;id&gt; ...</pre></li>
 * </ol>
 * <p>
 * Thread Safety: This class is NOT thread-safe. Each thread performing user lookup should create
 * its own instance. Request identifiers (requestIdCounter) are unique per instance, but socket I/O
 * is unsynchronized and must not be shared across threads.
 * <p>
 * Requirements:
 * <ul>
 *     <li>Java 16 or higher (native UNIX domain sockets)</li>
 *     <li>Unix/Linux OS (AF_UNIX socket support)</li>
 *     <li>File system permission to access the Dovecot auth-userdb socket</li>
 *     <li>Dovecot authentication service running with userdb access enabled</li>
 * </ul>
 * <p>
 * Usage Example:
 * <pre>
 * Path userdbSocket = Paths.get("/var/run/dovecot/auth-userdb");
 * try (DovecotUserLookupNative lookup = new DovecotUserLookupNative(userdbSocket)) {
 *     boolean exists = lookup.validate("user@example.com", "smtp");
 *     if (exists) {
 *         // proceed with authentication flow
 *     }
 * }
 * </pre>
 * <p>
 * Failure Handling: If the socket cannot be initialized (e.g. missing file, permission denied),
 * the streams will be null and validate() will log an error and return false. Callers may wish to
 * differentiate between "user not found" and "infrastructure failure" by monitoring logs.
 * <p>
 * Resource Management: Use try-with-resources or explicitly call {@link #close()} to ensure that
 * the underlying SocketChannel and streams are released promptly.
 * <p>
 * Extensibility: Future enhancements could parse additional fields from successful USER responses
 * (quota, home directory, uid/gid, etc.) and expose them via a richer result object instead of a
 * boolean.
 */
public class DovecotUserLookupNative implements AutoCloseable {
    protected static final Logger log = LogManager.getLogger(DovecotUserLookupNative.class);

    /**
     * Path to the Dovecot auth-userdb UNIX domain socket.
     */
    private final Path socketPath;

    /**
     * Counter for generating unique request IDs per lookup request.
     */
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    /**
     * Underlying UNIX domain socket channel.
     */
    protected SocketChannel channel;

    /** Output stream for sending protocol requests. */
    protected OutputStream outputStream;

    /** Input stream for receiving protocol responses. */
    protected InputStream inputStream;

    /**
     * Constructs a new DovecotUserLookupNative client and immediately attempts socket initialization.
     * If initialization fails, the instance remains usable for {@link #close()} but validate() will
     * return false and log an error.
     *
     * @param socketPath Path to the auth-userdb socket (e.g. /var/run/dovecot/auth-userdb).
     */
    public DovecotUserLookupNative(Path socketPath) {
        this.socketPath = socketPath;
        initSocket();
    }

    /**
     * Initializes the UNIX domain socket connection. Reads and logs any initial welcome banner once.
     * Errors are caught broadly to avoid propagating unchecked exceptions to callers.
     */
    void initSocket() {
        log.debug("Initializing user lookup unix socket at {}", socketPath);
        try {
            channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath));
            outputStream = Channels.newOutputStream(channel);
            inputStream = Channels.newInputStream(channel);
            readWelcomeOnce();
        } catch (Throwable e) {
            log.error("Failed to initialize user lookup unix socket at {}: {}", socketPath, e.toString());
        }
    }

    /**
     * Reads a one-time welcome banner (if provided by Dovecot) and logs it for diagnostic purposes.
     * Silently returns if no data is available.
     */
    private void readWelcomeOnce() throws IOException {
        byte[] buffer = new byte[1024];
        int read = inputStream.read(buffer);
        if (read > 0) {
            String welcome = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            logDebug(welcome, "welcome");
        }
    }

    /**
     * Validates (looks up) whether a user exists for a given service context.
     * <p>
     * Sends a USER request and expects either USER (success) or NOTFOUND (failure) response.
     * Currently returns a boolean; future versions may expose richer metadata.
     *
     * @param username User identifier to look up (e.g. user@example.com)
     * @param service  Service context (e.g. smtp, imap) for policy-specific userdb rules.
     * @return true if found (response starts with USER\t<id>), false otherwise or if socket uninitialized.
     * @throws IOException If an I/O error occurs while sending or receiving.
     */
    public boolean validate(String username, String service) throws IOException {
        if (outputStream == null || inputStream == null) {
            log.error("Socket is not initialized. Cannot perform user validation.");
            return false;
        }
        long requestId = requestIdCounter.getAndIncrement();
        String request = buildUserRequest(username, service, requestId);
        String response = exchangeSingle(request);
        boolean ok = response.startsWith("USER\t" + requestId);
        log.debug("User lookup {} for {}", ok ? "succeeded" : "failed", username);
        return ok;
    }

    /**
     * Builds a protocol-compliant USER request.
     */
    private String buildUserRequest(String username, String service, long requestId) {
        return "VERSION\t1\t0\n" +
                "USER\t" + requestId + "\t" + username + "\tservice=" + service + "\n";
    }

    /**
     * Performs a single request/response round-trip for user lookup. Response is trimmed and logged.
     */
    private String exchangeSingle(String request) throws IOException {
        logDebug(request, "request");
        outputStream.write(request.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        byte[] buffer = new byte[2048];
        int read = inputStream.read(buffer);
        if (read <= 0) return "";
        String response = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        logDebug(response, "response");
        return response;
    }

    /**
     * Logs multi-line protocol messages for debugging clarity.
     */
    private void logDebug(String message, String phase) {
        log.debug("UserLookup socket {}:", phase);
        for (String line : message.split("\r?\n")) {
            if (!line.isEmpty()) log.debug("<< {}", line);
        }
    }

    /**
     * Closes the socket channel and associated streams, swallowing and logging any exceptions.
     */
    @Override
    public void close() {
        try {
            if (channel != null) channel.close();
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
        } catch (Exception e) {
            log.error("Error closing user lookup socket: {}", e.getMessage());
        }
    }
}
