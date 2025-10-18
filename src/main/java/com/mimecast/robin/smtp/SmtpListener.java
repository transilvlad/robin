package com.mimecast.robin.smtp;

import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SMTP socket listener.
 *
 * <p>This runs a ServerSocket bound to configured interface and port.
 * <p>An email receipt instance will be constructed for each accepted connection.
 *
 * @see EmailReceipt
 */
public class SmtpListener {
    private static final Logger log = LogManager.getLogger(SmtpListener.class);

    /**
     * ServerSocket instance.
     */
    private ServerSocket listener;

    /**
     * ThreadPoolExecutor instance.
     */
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    /**
     * Server shutdown boolean.
     */
    private boolean serverShutdown = false;

    private final int port;
    private final int backlog;
    private final String bind;
    private final boolean secure;
    private final boolean submission;

    /**
     * Constructs a new SmtpListener instance.
     *
     * @param port       Port number.
     * @param backlog    Backlog size.
     * @param bind       Interface to bind to.
     * @param secure     Secure (TLS) listener.
     * @param submission Submission (MSA) listener.
     */
    public SmtpListener(int port, int backlog, String bind, boolean secure, boolean submission) {
        this.port = port;
        this.backlog = backlog;
        this.bind = bind;
        this.secure = secure;
        this.submission = submission;

        configure();
    }

    /**
     * Starts the listener.
     * <p>This method opens the server socket and enters a loop to accept connections.
     */
    public void listen() {
        try {
            listener = new ServerSocket(port, backlog, InetAddress.getByName(bind));
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
     * Configure thread pool.
     */
    protected void configure() {
        executor.setKeepAliveTime(Config.getServer().getThreadKeepAliveTime(), TimeUnit.SECONDS);
        executor.setCorePoolSize(Config.getServer().getMinimumPoolSize());
        executor.setMaximumPoolSize(Config.getServer().getMaximumPoolSize());
        // Avoid rejecting new tasks when the pool is saturated; run in the caller thread instead.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Accept incoming connection.
     */
    private void acceptConnection() {
        try {
            do {
                Socket sock = listener.accept();
                log.info("Accepted connection from {}:{} on port {}.", sock.getInetAddress().getHostAddress(), sock.getPort(), port);

                executor.submit(() -> {
                    try {
                        new EmailReceipt(sock, secure, submission).run();

                    } catch (Exception e) {
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
     * Shutdown.
     *
     * @throws IOException Unable to communicate.
     */
    public void serverShutdown() throws IOException {
        serverShutdown = true;
        if (listener != null) {
            listener.close();
        }
        executor.shutdown();
    }

    /**
     * Gets listener.
     *
     * @return ServerSocket instance.
     */
    public ServerSocket getListener() {
        return listener;
    }

    /**
     * Gets the port this listener is on.
     *
     * @return Port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the number of active threads in this listener's pool.
     *
     * @return Active thread count.
     */
    public int getActiveThreads() {
        return executor.getActiveCount();
    }

    // Additional thread pool stats for health reporting

    /**
     * @return Current core pool size.
     */
    public int getCorePoolSize() {
        return executor.getCorePoolSize();
    }

    /**
     * @return Configured maximum pool size.
     */
    public int getMaximumPoolSize() {
        return executor.getMaximumPoolSize();
    }

    /**
     * @return Current pool size (number of threads in the pool).
     */
    public int getPoolSize() {
        return executor.getPoolSize();
    }

    /**
     * @return Largest pool size reached since the executor started.
     */
    public int getLargestPoolSize() {
        return executor.getLargestPoolSize();
    }

    /**
     * @return Queue size for pending tasks (0 for SynchronousQueue in cached thread pool).
     */
    public int getQueueSize() {
        return executor.getQueue() != null ? executor.getQueue().size() : 0;
    }

    /**
     * @return Approximate total number of tasks that have completed execution.
     */
    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    /**
     * @return Approximate total number of tasks that have ever been scheduled for execution.
     */
    public long getTaskCount() {
        return executor.getTaskCount();
    }

    /**
     * @return Keep-alive time for idle threads in seconds.
     */
    public long getKeepAliveSeconds() {
        return executor.getKeepAliveTime(TimeUnit.SECONDS);
    }
}