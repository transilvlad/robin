package com.mimecast.robin.smtp;

import com.mimecast.robin.config.server.ListenerConfig;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SMTP socket listener for handling client connections.
 * <p>This class runs a {@link ServerSocket} bound to a configured network interface and port.
 * <p>For each accepted connection, it creates an {@link EmailReceipt} instance to handle the SMTP session.
 * <p>It uses a {@link ThreadPoolExecutor} to manage concurrent connections efficiently.
 *
 * @see EmailReceipt
 * @see Server
 */
public class SmtpListener {
    private static final Logger log = LogManager.getLogger(SmtpListener.class);

    /**
     * The underlying server socket that listens for incoming connections.
     */
    private ServerSocket listener;

    /**
     * Thread pool for handling client connections.
     * Using a ThreadPoolExecutor allows for fine-tuning of thread management,
     * which is crucial for performance and resource control.
     */
    private final ThreadPoolExecutor executor;

    /**
     * Flag to indicate a server shutdown is in progress.
     * This is used to gracefully stop the connection acceptance loop.
     */
    private volatile boolean serverShutdown = false;

    private final int port;
    private final String bind;
    private final boolean secure;
    private final boolean submission;
    private final ListenerConfig config;

    /**
     * Constructs a new SmtpListener instance with the specified configuration.
     *
     * @param port       The port number to listen on.
     * @param bind       The network interface address to bind to.
     * @param config     The {@link ListenerConfig} containing listener-specific settings.
     * @param secure     {@code true} if the listener should handle secure (TLS) connections.
     * @param submission {@code true} if the listener is for mail submission (MSA).
     */
    public SmtpListener(int port, String bind, ListenerConfig config, boolean secure, boolean submission) {
        this.port = port;
        this.bind = bind;
        this.config = config;
        this.secure = secure;
        this.submission = submission;

        // Initialize and configure the thread pool executor.
        this.executor = new ThreadPoolExecutor(
                config.getMinimumPoolSize(),
                config.getMaximumPoolSize(),
                config.getThreadKeepAliveTime(), TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Starts the listener.
     * <p>This method opens the server socket and enters a loop to accept incoming connections.
     * Each new connection is passed to the thread pool for processing.
     */
    public void listen() {
        try {
            listener = new ServerSocket(port, config.getBacklog(), InetAddress.getByName(bind));
            log.info("Listening to [{}]:{}", bind, port);

            acceptConnection();

        } catch (IOException e) {
            log.fatal("Error listening: {}", e.getMessage());

        } finally {
            try {
                if (listener != null && !listener.isClosed()) {
                    listener.close();
                    log.info("Closed listener for port {}.", port);
                }
                executor.shutdown();
            } catch (Exception e) {
                log.info("Listener for port {} already closed.", port);
            }
        }
    }

    /**
     * Accepts incoming connections in a loop until a shutdown is initiated.
     * For each connection, it submits a new {@link EmailReceipt} task to the thread pool.
     */
    private void acceptConnection() {
        try {
            do {
                Socket sock = listener.accept();
                log.info("Accepted connection from {}:{} on port {}.", sock.getInetAddress().getHostAddress(), sock.getPort(), port);

                executor.submit(() -> {
                    try {
                        new EmailReceipt(sock, config, secure, submission).run();

                    } catch (Exception e) {
                        SmtpMetrics.incrementEmailReceiptException(e.getClass().getSimpleName());
                        log.error("Email receipt unexpected exception: {}", e.getMessage());
                    }
                    return null;
                });
            } while (!serverShutdown);

        } catch (SocketException e) {
            if (!serverShutdown) {
                log.info("Error in socket exchange: {}", e.getMessage());
            }
        } catch (IOException e) {
            log.info("Error reading/writing: {}", e.getMessage());
        }
    }

    /**
     * Initiates a graceful shutdown of the listener.
     * <p>This method gracefully shuts down the listener by closing the server socket
     * and terminating the thread pool.
     *
     * @throws IOException If an I/O error occurs when closing the socket.
     */
    public void serverShutdown() throws IOException {
        serverShutdown = true;
        if (listener != null) {
            listener.close();
        }
        executor.shutdown();
    }

    /**
     * Gets the underlying {@link ServerSocket} instance.
     *
     * @return The {@link ServerSocket} instance.
     */
    public ServerSocket getListener() {
        return listener;
    }

    /**
     * Gets the port number this listener is configured to use.
     *
     * @return The port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the number of currently active threads in this listener's thread pool.
     *
     * @return The number of active threads.
     */
    public int getActiveThreads() {
        return executor.getActiveCount();
    }

    // Additional thread pool stats for health reporting

    /**
     * Gets the core number of threads for the thread pool.
     *
     * @return The core pool size.
     */
    public int getCorePoolSize() {
        return executor.getCorePoolSize();
    }

    /**
     * Gets the maximum allowed number of threads for the thread pool.
     *
     * @return The maximum pool size.
     */
    public int getMaximumPoolSize() {
        return executor.getMaximumPoolSize();
    }

    /**
     * Gets the current number of threads in the pool.
     *
     * @return The current pool size.
     */
    public int getPoolSize() {
        return executor.getPoolSize();
    }

    /**
     * Gets the largest number of threads that have ever simultaneously been in the pool.
     *
     * @return The largest pool size reached.
     */
    public int getLargestPoolSize() {
        return executor.getLargestPoolSize();
    }

    /**
     * Gets the current size of the task queue.
     * For a {@link SynchronousQueue}, this will always be 0.
     *
     * @return The number of tasks in the queue.
     */
    public int getQueueSize() {
        return executor.getQueue() != null ? executor.getQueue().size() : 0;
    }

    /**
     * Gets the approximate total number of tasks that have completed execution.
     *
     * @return The completed task count.
     */
    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    /**
     * Gets the approximate total number of tasks that have ever been scheduled for execution.
     *
     * @return The total task count.
     */
    public long getTaskCount() {
        return executor.getTaskCount();
    }

    /**
     * Gets the keep-alive time for idle threads in seconds.
     *
     * @return The thread keep-alive time in seconds.
     */
    public long getKeepAliveSeconds() {
        return executor.getKeepAliveTime(TimeUnit.SECONDS);
    }
}
