package com.mimecast.robin.main;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.config.DovecotConfig;
import com.mimecast.robin.db.SharedDataSource;
import com.mimecast.robin.endpoints.ApiEndpoint;
import com.mimecast.robin.endpoints.RobinServiceEndpoint;
import com.mimecast.robin.metrics.MetricsCron;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.smtp.SmtpListener;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import com.mimecast.robin.storage.LmtpConnectionPool;
import com.mimecast.robin.storage.StorageCleaner;
import com.mimecast.robin.util.VaultClient;
import com.mimecast.robin.util.VaultClientFactory;
import com.mimecast.robin.util.VaultMagicProvider;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main server class for the Robin SMTP server.
 *
 * <p>This class is responsible for initializing and managing the server's lifecycle.
 * <p>It loads configurations, sets up SMTP listeners, and starts various background services
 * such as service, API endpoints, and queue processing.
 *
 * <p>The server is started by calling the static {@link #run(String)} method with the path
 * to the configuration directory.
 *
 * <p>This class also handles graceful shutdown of the server and its components.
 *
 * @see SmtpListener
 * @see Foundation
 */
@SuppressWarnings("squid:S106")
public class Server extends Foundation {

    /**
     * List of active SMTP listener instances.
     * Using a standard ArrayList as the list is populated at startup and not modified thereafter.
     */
    private static final List<SmtpListener> listeners = new ArrayList<>();

    /**
     * Executor service for running the SMTP listeners in separate threads.
     * This provides better resource management than creating individual threads.
     */
    private static ExecutorService listenerExecutor;

    /**
     * Executor service for processing bot requests.
     * <p>Bots analyze incoming emails and generate automated responses.
     * <p>Using a cached thread pool that creates threads on demand and reuses idle threads.
     */
    protected static ExecutorService botExecutor;

    /**
     * LMTP connection pool for limiting concurrent Dovecot LMTP deliveries.
     * <p>Prevents overwhelming Dovecot with too many concurrent connections.
     * <p>Only initialized when LMTP backend is enabled in dovecot configuration.
     */
    protected static LmtpConnectionPool lmtpPool;

    /**
     * Initializes and starts the Robin SMTP server.
     *
     * @param path The directory path containing the configuration files.
     * @throws ConfigurationException If there is an issue with the configuration files.
     */
    public static void run(String path) throws ConfigurationException {
        init(path); // Initialize foundation configuration.
        registerShutdownHook(); // Register shutdown hook for graceful termination.
        loadKeystore(); // Load SSL keystore.
        startup(); // Start prerequisite services.

        // Create SMTP listeners based on configuration.
        ServerConfig serverConfig = Config.getServer();

        // Standard SMTP listener.
        if (serverConfig.getSmtpPort() != 0) {
            listeners.add(new SmtpListener(
                    serverConfig.getSmtpPort(),
                    serverConfig.getBind(),
                    serverConfig.getSmtpConfig(),
                    false,
                    false
            ));
        }

        // Secure SMTP (SMTPS) listener.
        if (serverConfig.getSecurePort() != 0) {
            listeners.add(new SmtpListener(
                    serverConfig.getSecurePort(),
                    serverConfig.getBind(),
                    serverConfig.getSecureConfig(),
                    true,
                    true
            ));
        }

        // Submission listener (MSA).
        if (serverConfig.getSubmissionPort() != 0) {
            listeners.add(new SmtpListener(
                    serverConfig.getSubmissionPort(),
                    serverConfig.getBind(),
                    serverConfig.getSubmissionConfig(),
                    false,
                    true
            ));
        }

        // Start listeners in the thread pool.
        if (!listeners.isEmpty()) {
            listenerExecutor = Executors.newFixedThreadPool(listeners.size());
            for (SmtpListener listener : listeners) {
                listenerExecutor.submit(listener::listen);
            }
        }
    }

    /**
     * Starts up the prerequisite services for the server.
     * This includes storage cleaning, queue management, service and API endpoints.
     */
    private static void startup() {
        // Initialize Vault integration for secrets management.
        initializeVault();

        // Clean storage directory on startup.
        StorageCleaner.clean(Config.getServer().getStorage());

        // Start the relay queue cron job for processing queued messages.
        RelayQueueCron.run();

        // Start the service endpoint for monitoring.
        try {
            RobinServiceEndpoint serviceEndpoint = new RobinServiceEndpoint();
            serviceEndpoint.start(Config.getServer().getService());
            SmtpMetrics.initialize();
        } catch (IOException e) {
            log.error("Unable to start monitoring endpoint: {}", e.getMessage());
        }

        // Start the metrics remote write cron if configured.
        try {
            MetricsCron.run(Config.getServer().getPrometheus());
        } catch (Exception e) {
            log.error("Unable to start metrics cron: {}", e.getMessage());
        }

        // Start the API endpoint.
        try {
            new ApiEndpoint().start(Config.getServer().getApi());
        } catch (IOException e) {
            log.error("Unable to start API endpoint: {}", e.getMessage());
        }

        // Initialize bot executor service.
        botExecutor = Executors.newCachedThreadPool();
        log.info("Bot processing thread pool initialized");

        ServerConfig serverConfig = Config.getServer();

        // Initialize LMTP connection pool if LMTP backend is enabled.
        try {
            DovecotConfig dovecotConfig = serverConfig.getDovecot();
            DovecotConfig.SaveLmtp lmtpConfig = dovecotConfig.getSaveLmtp();
            if (lmtpConfig.isEnabled()) {
                lmtpPool = new LmtpConnectionPool(
                        lmtpConfig.getConnectionPoolSize(),
                        lmtpConfig.getConnectionPoolTimeoutSeconds(),
                        lmtpConfig.getConnectionIdleTimeoutSeconds(),
                        lmtpConfig.getConnectionMaxLifetimeSeconds(),
                        lmtpConfig.getServers(),
                        lmtpConfig.getPort(),
                        lmtpConfig.isTls()
                );
            }
        } catch (Exception e) {
            log.warn("Failed to initialize LMTP connection pool: {}", e.getMessage());
        }

        // Initialize shared DataSource if SQL auth backend is configured.
        try {
            DovecotConfig dovecotConfig = serverConfig.getDovecot();
            if (dovecotConfig.isAuthSqlEnabled()) {
                // Initialize shared pool (lazy init in SharedDataSource.getDataSource())
                var ds = SharedDataSource.getDataSource();
                // Initialize shared Sql providers for auth and user lookup
                SqlAuthManager.init(ds);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize shared SQL datasource: {}", e.getMessage());
        }
    }

    /**
     * Initializes HashiCorp Vault integration for secrets management.
     * Vault secrets can be used as magic variables in configurations.
     * Secrets are fetched on-demand and cached for performance.
     */
    private static void initializeVault() {
        try {
            ServerConfig serverConfig = Config.getServer();
            VaultClient vaultClient = VaultClientFactory.createFromConfig(serverConfig);
            VaultMagicProvider.initialize(vaultClient);

            if (vaultClient.isEnabled()) {
                log.info("Vault integration initialized successfully - secrets will be fetched on-demand");
            }
        } catch (Exception e) {
            log.error("Failed to initialize Vault integration: {}", e.getMessage());
            log.warn("Continuing without Vault integration");
        }
    }


    /**
     * Registers a shutdown hook to ensure graceful termination of the server.
     * This hook will be called by the JVM on shutdown.
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Service is shutting down.");

            // Shutdown all active listeners.
            for (SmtpListener listener : listeners) {
                if (listener != null && listener.getListener() != null) {
                    try {
                        listener.serverShutdown();
                    } catch (IOException e) {
                        log.error("Error shutting down listener on port {}: {}", listener.getPort(), e.getMessage());
                    }
                }
            }

            // Shutdown the listener executor service.
            if (listenerExecutor != null) {
                listenerExecutor.shutdown();
                try {
                    if (!listenerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        listenerExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    listenerExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Shutdown the bot executor service.
            if (botExecutor != null) {
                botExecutor.shutdown();
                try {
                    if (!botExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.warn("Bot executor did not terminate in time, forcing shutdown");
                        botExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    botExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Close LMTP connection pool if initialized.
            if (lmtpPool != null) {
                lmtpPool.close();
            }

            // Close shared DataSource if initialized.
            try {
                // Close shared SqlAuthManager first
                try { SqlAuthManager.close(); } catch (Exception ignore) {}
                SharedDataSource.close();
            } catch (Exception e) {
                log.warn("Error closing shared DataSource: {}", e.getMessage());
            }

            log.info("Shutdown complete.");
        }));
    }

    /**
     * Loads the SSL keystore for secure connections.
     * The keystore path and password are read from the server configuration.
     */
    private static void loadKeystore() {
        ServerConfig serverConfig = Config.getServer();
        String keyStorePath = serverConfig.getKeyStore();
        String keyStorePasswordPath = serverConfig.getKeyStorePassword();

        // Verify that the keystore file is readable.
        try {
            Files.readAllBytes(Paths.get(keyStorePath));
        } catch (IOException e) {
            log.error("Error reading keystore file [{}]: {}", keyStorePath, e.getMessage());
            return;
        }
        System.setProperty("javax.net.ssl.keyStore", keyStorePath);

        // Read keystore password from file or use plain text from config.
        String keyStorePassword;
        try {
            keyStorePassword = new String(Files.readAllBytes(Paths.get(keyStorePasswordPath)));
        } catch (IOException e) {
            log.warn("Keystore password could not be read from file, treating as plain text.");
            keyStorePassword = keyStorePasswordPath;
        }
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
    }

    /**
     * Gets the list of active {@link SmtpListener} instances.
     *
     * @return A list of {@link SmtpListener}s.
     */
    public static List<SmtpListener> getListeners() {
        return listeners;
    }

    /**
     * Gets the bot processing executor service.
     *
     * @return ExecutorService for bot processing, or null if not initialized.
     */
    public static ExecutorService getBotExecutor() {
        return botExecutor;
    }

    /**
     * Gets the LMTP connection pool.
     *
     * @return LmtpConnectionPool for limiting concurrent LMTP deliveries, or null if not initialized.
     */
    public static LmtpConnectionPool getLmtpPool() {
        return lmtpPool;
    }
}
