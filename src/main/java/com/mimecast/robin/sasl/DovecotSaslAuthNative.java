package com.mimecast.robin.sasl;

import com.mimecast.robin.main.Config;
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
public class DovecotSaslAuthNative implements AutoCloseable {
    protected static final Logger log = LogManager.getLogger(DovecotSaslAuthNative.class);

    // Default path to the Dovecot authentication socket.
    private static final String DEFAULT_DOVECOT_AUTH_SOCKET_PATH = Config.getServer().getDovecotAuthSocket();

    // The path to the Dovecot authentication socket.
    private final Path socketPath;

    // A simple counter to ensure unique request IDs within this client instance.
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    // Socket with output stream and input stream.
    protected Socket socket;
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
        initSocket(UnixDomainSocketAddress.of(socketPath));
    }

    /**
     * Initializes the UNIX domain socket connection.
     *
     * @param address The UnixDomainSocketAddress to connect to.
     */
    @SuppressWarnings("resource")
    void initSocket(UnixDomainSocketAddress address) {
        try {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            if (channel.connect(address)) {
                socket = channel.socket();
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
            }
        } catch (IOException e) {
            log.error("Failed to initialize unix socket at {}: {}", socketPath, e.getMessage());
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
        String request = buildUserRequest(username, service);

        // A successful response starts with "RESULT<TAB>OK".
        boolean response = callDovecotSocket(request).startsWith("RESULT\tOK\t");

        log.info("Validation {} for user: {}", response ? "succeeded" : "failed", username);
        return response;
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
        boolean response = callDovecotSocket(request).startsWith("OK\t" + requestId);

        log.info("Authentication {} for user: {}", response ? "succeeded" : "failed", username);
        return response;
    }

    /**
     * Constructs the validation request string.
     *
     * @param username The username to validate.
     * @param service  The service name (e.g., "smtp").
     * @return The constructed request string.
     */
    private String buildUserRequest(String username, String service) {
        return "VERSION\t1\t" +
                "COMMAND\tUSER\t" +
                "USERNAME\t" + username + "\t" +
                "SERVICE\t" + service + "\n";
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
     * Sends a request to the Dovecot socket and reads the response.
     *
     * @param request The request string to send.
     * @return The response string from the socket.
     * @throws IOException if there's an error communicating with the socket.
     */
    private String callDovecotSocket(String request) throws IOException {
        String[] splits = request.trim().split("\n");
        log.info("Sending request:");
        for (String s : splits) {
            log.info(">> {}", s);
        }

        outputStream.write(request.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        byte[] buffer = new byte[1024];
        int bytesRead = inputStream.read(buffer);

        String response = "";
        if (bytesRead > 0) {
            response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8).trim();
            log.info("Received response:");

            splits = response.trim().split("\n");
            for (String s : splits) {
                log.debug("<< {}", s);
            }
        }

        return response;
    }

    /**
     * Closes the socket and associated streams.
     *
     * @throws Exception if there's an error closing the resources.
     */
    @Override
    public void close() throws Exception {
        socket.close();
        inputStream.close();
        outputStream.close();
    }
}