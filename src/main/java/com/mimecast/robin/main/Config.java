package com.mimecast.robin.main;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.Properties;
import com.mimecast.robin.config.client.ClientConfig;
import com.mimecast.robin.config.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Master configuration initializer and container.
 *
 * <p>Properties is designed to provide a generic container for extendability.
 * <p>This can also be used to access syetm properties.
 * <p>It will favor system properties over properties file values.
 *
 * <p>ServerConfig configuration holds the server config, authentication users and behaviour scenarios.
 * <p>ClientConfig configuration holds the client defaults and routes.
 *
 * @see Properties
 * @see ServerConfig
 * @see ClientConfig
 */
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    /**
     * Protected constructor.
     */
    private Config() {
        throw new IllegalStateException("Static class");
    }

    /**
     * SystemProperties or properties file configuration container.
     */
    private static Properties properties = new Properties();

    /**
     * Server configuration.
     */
    private static ServerConfig server = new ServerConfig();

    /**
     * Client default configuration.
     */
    private static ClientConfig client = new ClientConfig();

    /**
     * Gets properties.
     *
     * @return Properties.
     */
    public static Properties getProperties() {
        return properties;
    }

    /**
     * Scheduled executor service for config reloading.
     */
    private final static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Map to track scheduled futures by task type.
     */
    private static final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    /**
     * Init properties.
     * <p>
     *
     * @param path File path.
     * @throws IOException Unable to read file.
     */
    public static void initProperties(String path) throws IOException {
        properties = new Properties(path);
        if (!scheduledFutures.containsKey("properties")) {
            BasicConfig propertiesAutoReload = properties.getPropertiesAutoReload();
            if (propertiesAutoReload.getBooleanProperty("enabled")) {
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                            try {
                                properties = new Properties(path);
                                log.debug("Reloaded properties file: {}", path);
                            } catch (IOException e) {
                                log.error("Failed to reload properties file: {}, error {}", path, e.getMessage());
                            }
                        },
                        propertiesAutoReload.getLongProperty("delaySeconds", 300L),
                        properties.getLongProperty("intervalSeconds", 300L),
                        TimeUnit.SECONDS
                );
                scheduledFutures.put("properties", future);
                log.info("Scheduled properties file reload: {}, delay: {} seconds, interval: {} seconds", path,
                        propertiesAutoReload.getLongProperty("delaySeconds", 300L),
                        properties.getLongProperty("intervalSeconds", 300L));
            }
        }
    }

    /**
     * Gets server config.
     *
     * @return ServerConfig.
     */
    public static ServerConfig getServer() {
        return server;
    }

    /**
     * Init server config.
     *
     * @param path File path.
     * @throws IOException Unable to read file.
     */
    public static void initServer(String path) throws IOException {
        server = new ServerConfig(path);
        if (!scheduledFutures.containsKey("server")) {
            BasicConfig serverAutoReload = properties.getServerAutoReload();
            if (serverAutoReload.getBooleanProperty("enabled")) {
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                            try {
                                server = new ServerConfig(path);
                                log.debug("Reloaded server file: {}", path);
                            } catch (IOException e) {
                                log.error("Failed to reload server file: {}, error {}", path, e.getMessage());
                            }
                        },
                        serverAutoReload.getLongProperty("delaySeconds", 300L),
                        properties.getLongProperty("intervalSeconds", 300L),
                        TimeUnit.SECONDS
                );
                scheduledFutures.put("server", future);
                log.info("Scheduled server file reload: {}, delay: {} seconds, interval: {} seconds", path,
                        serverAutoReload.getLongProperty("delaySeconds", 300L),
                        properties.getLongProperty("intervalSeconds", 300L));
            }
        }
    }

    /**
     * Gets client config.
     *
     * @return ClientConfig.
     */
    public static ClientConfig getClient() {
        return client;
    }

    /**
     * Init client config.
     *
     * @param path File path.
     * @throws IOException Unable to read file.
     */
    public static void initClient(String path) throws IOException {
        client = new ClientConfig(path);
    }
}
