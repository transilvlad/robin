package com.mimecast.robin.main;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.Properties;
import com.mimecast.robin.config.client.ClientConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.config.store.ConfigStoreSyncManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Logger log = LogManager.getLogger(Config.class);

    /**
     * Protected constructor.
     */
    private Config() {
        throw new IllegalStateException("Static class");
    }

    /**
     * SystemProperties or properties file configuration container.
     */
    private static volatile Properties properties = new Properties();

    /**
     * Server configuration.
     */
    private static volatile ServerConfig server = new ServerConfig();
    private static volatile long snapshotLoadedAtEpochMillis;
    private static volatile boolean restartRequired;
    private static final List<String> RESTART_COMPONENTS = List.of("listeners", "sqlAuth", "queue", "scanners");

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
     * Paths to configuration files for reload operations.
     */
    private static String propertiesPath;
    private static String serverPath;

    /**
     * Init properties.
     * <p>
     *
     * @param path File path.
     * @throws IOException Unable to read file.
     */
    public static void initProperties(String path) throws IOException {
        propertiesPath = path;
        properties = new Properties(path);
        if (!scheduledFutures.containsKey("properties")) {
            BasicConfig propertiesAutoReload = properties.getPropertiesAutoReload();
            if (propertiesAutoReload.getBooleanProperty("enabled")) {
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                            try {
                                Properties nextProperties = new Properties(path);
                                synchronized (Config.class) {
                                    properties = nextProperties;
                                    snapshotLoadedAtEpochMillis = System.currentTimeMillis();
                                    restartRequired = true;
                                }
                                log.debug("Reloaded properties file: {}", path);
                            } catch (IOException | RuntimeException e) {
                                log.error("Properties auto-reload failed; prior snapshot retained");
                            }
                        },
                        propertiesAutoReload.getLongProperty("delaySeconds", 300L),
                        propertiesAutoReload.getLongProperty("intervalSeconds", 300L),
                        TimeUnit.SECONDS
                );
                scheduledFutures.put("properties", future);
                log.info("Scheduled properties file reload: {}, delay: {} seconds, interval: {} seconds", path,
                        propertiesAutoReload.getLongProperty("delaySeconds", 300L),
                        propertiesAutoReload.getLongProperty("intervalSeconds", 300L));
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
        serverPath = path;
        server = new ServerConfig(path);
        snapshotLoadedAtEpochMillis = System.currentTimeMillis();
        restartRequired = false;
        if (!scheduledFutures.containsKey("server")) {
            BasicConfig serverAutoReload = properties.getServerAutoReload();
            if (serverAutoReload.getBooleanProperty("enabled")) {
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                            try {
                                ServerConfig nextServer = new ServerConfig(path);
                                nextServer.loadExternalConfigurations();
                                synchronized (Config.class) {
                                    server = nextServer;
                                    snapshotLoadedAtEpochMillis = System.currentTimeMillis();
                                    restartRequired = true;
                                }
                                log.debug("Reloaded server file: {}", path);
                            } catch (IOException | RuntimeException e) {
                                log.error("Server auto-reload failed; prior snapshot retained");
                            }
                        },
                        serverAutoReload.getLongProperty("delaySeconds", 300L),
                        serverAutoReload.getLongProperty("intervalSeconds", 300L),
                        TimeUnit.SECONDS
                );
                scheduledFutures.put("server", future);
                log.info("Scheduled server file reload: {}, delay: {} seconds, interval: {} seconds", path,
                        serverAutoReload.getLongProperty("delaySeconds", 300L),
                        serverAutoReload.getLongProperty("intervalSeconds", 300L));
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

    /**
     * Triggers an immediate reload of configuration.
     * Scheduling controls automatic reload only; manual reload always parses the
     * configured files. Existing listeners and service pools still require restart.
     */
    public static synchronized Map<String, Object> triggerReload() {
        try {
            ConfigStoreSyncManager.syncIfEnabled();
            if (ConfigStoreSyncManager.getStatus().toMap().containsKey("lastError")) {
                throw new IllegalStateException("Configuration store synchronization failed");
            }
            if (propertiesPath == null || serverPath == null) {
                throw new IllegalStateException("Configuration paths are not initialized");
            }
            Properties nextProperties = new Properties(propertiesPath);
            if (nextProperties.hasProperty("properties")) {
                Path custom = Path.of(propertiesPath).getParent().resolve(nextProperties.getStringProperty("properties"));
                if (Files.isRegularFile(custom)) {
                    nextProperties.merge(new Properties(custom.toString()));
                }
            }
            ServerConfig nextServer = new ServerConfig(serverPath);
            nextServer.loadExternalConfigurations();
            properties = nextProperties;
            server = nextServer;
            snapshotLoadedAtEpochMillis = System.currentTimeMillis();
            restartRequired = true;
            Map<String, Object> result = new LinkedHashMap<>(getReloadStatus());
            result.put("status", "success");
            result.put("applied", true);
            result.put("reloaded", List.of("properties", "server"));
            result.put("failed", List.of());
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Configuration could not be loaded; prior snapshot retained", e);
        }
    }

    public static synchronized Map<String, Object> getReloadStatus() {
        Map<String, Object> sourceFiles = new LinkedHashMap<>();
        sourceFiles.putAll(properties.getLoadedSourceFiles());
        sourceFiles.putAll(server.getLoadedSourceFiles());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("scope", "configuration-snapshot");
        status.put("snapshotLoadedAtEpochMillis", snapshotLoadedAtEpochMillis);
        status.put("manualReloadAvailable", propertiesPath != null && serverPath != null);
        status.put("autoReload", Map.of(
                "properties", scheduledFutures.containsKey("properties"),
                "server", scheduledFutures.containsKey("server")));
        status.put("sourceFiles", sourceFiles);
        status.put("restartRequired", restartRequired);
        status.put("restartRequiredComponents", restartRequired ? RESTART_COMPONENTS : List.of());
        status.put("sync", ConfigStoreSyncManager.getStatus().toMap());
        return status;
    }
}
