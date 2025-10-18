package com.mimecast.robin.main;

import com.mimecast.robin.endpoints.ClientEndpoint;
import com.mimecast.robin.endpoints.MetricsEndpoint;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.smtp.SmtpListener;
import com.mimecast.robin.storage.StorageCleaner;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Socket listener.
 *
 * <p>This is the means by which the server is started.
 * <p>It's initialized with a configuration dir path.
 * <p>The configuration path is used to load the global configuration files.
 * <p>Loads both client and server configuration files.
 *
 * @see SmtpListener
 */
public class Server extends Foundation {

    /**
     * Listener instances.
     */
    private static final List<SmtpListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Runner.
     *
     * @param path Directory path.
     * @throws ConfigurationException Unable to read/parse config file.
     */
    public static void run(String path) throws ConfigurationException {
        init(path); // Initialize foundation.
        registerShutdown(); // Shutdown hook.
        loadKeystore(); // Load Keystore.

        // Create listeners and add them to the list.
        List<Integer> ports = List.of(
                Config.getServer().getPort(),
                Config.getServer().getSecurePort(),
                Config.getServer().getSubmissionPort()
        );

        for (int port : ports) {
            if (port != 0) {
                listeners.add(new SmtpListener(
                        port,
                        Config.getServer().getBacklog(),
                        Config.getServer().getBind(),
                        port == Config.getServer().getSecurePort(),
                        port == Config.getServer().getSubmissionPort()
                ));
            }
        }

        startup(); // Startup prerequisites, including MetricsEndpoint.

        // Start listeners in separate threads.
        for (SmtpListener listener : listeners) {
            new Thread(listener::listen).start();
        }
    }

    /**
     * Startup prerequisites.
     */
    private static void startup() {
        // Start relay queue cron job.
        RelayQueueCron.run();

        // Clean storage on startup.
        StorageCleaner.clean(Config.getServer().getStorage());

        // Start metrics endpoint.
        try {
            new MetricsEndpoint().start();
        } catch (IOException e) {
            log.error("Unable to start monitoring endpoint: {}", e.getMessage());
        }

        // Start client submission endpoint.
        try {
            new ClientEndpoint().start();
        } catch (IOException e) {
            log.error("Unable to start client submission endpoint: {}", e.getMessage());
        }
    }

    /**
     * Shutdown hook.
     */
    private static void registerShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (SmtpListener listener : listeners) {
                if (listener != null && listener.getListener() != null) {
                    log.info("Service is shutting down.");
                    try {
                        listener.serverShutdown();
                    } catch (IOException e) {
                        log.info("Shutdown in progress.. please wait.");
                    }
                }
            }
        }));
    }

    /**
     * Load Keystore.
     */
    private static void loadKeystore() {
        // Check keystore file is readable.
        try {
            Files.readAllBytes(Paths.get(Config.getServer().getKeyStore()));
        } catch (IOException e) {
            log.error("Error reading keystore file: {}", e.getMessage());
        }
        System.setProperty("javax.net.ssl.keyStore", Config.getServer().getKeyStore());

        // Read keystore password from file.
        String keyStorePassword;
        try {
            keyStorePassword = new String(Files.readAllBytes(Paths.get(Config.getServer().getKeyStorePassword())));
        } catch (IOException e) {
            log.warn("Keystore password treated as text.");
            keyStorePassword = Config.getServer().getKeyStorePassword();
        }
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
    }

    /**
     * Get listeners.
     *
     * @return List of SmtpListener.
     */
    public static List<SmtpListener> getListeners() {
        return listeners;
    }
}