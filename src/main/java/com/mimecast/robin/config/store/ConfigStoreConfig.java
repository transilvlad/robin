package com.mimecast.robin.config.store;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.util.PathUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Configuration for the PostgreSQL-backed configuration store.
 *
 * <p>Loaded from {@code config.json5} in the active configuration directory.
 * When enabled, the store is used by {@link ConfigStoreSyncManager} to seed and synchronize
 * {@code *.json5} configuration files.
 * The {@code serverId} selects the per-instance override layer that is merged on top of the base
 * configuration stored under {@code server_id = "default"}.
 */
record ConfigStoreConfig(
        boolean enabled,
        String serverId,
        String jdbcUrl,
        String username,
        String password,
        String tableName,
        int maxPoolSize,
        boolean required
) {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/robin";

    /**
     * Loads {@code config.json5} from the given directory.
     *
     * <p>A missing file disables the feature. An unreadable bootstrap is an error,
     * not an implicit opt-out of required synchronization.
     *
     * @param configDir Configuration directory.
     * @return Optional config-store config.
     */
    static Optional<ConfigStoreConfig> load(String configDir) {
        if (configDir == null) {
            return Optional.empty();
        }

        Path path = Paths.get(configDir).resolve("config.json5");
        if (!PathUtils.isFile(path.toString())) {
            return Optional.empty();
        }

        try {
            BasicConfig cfg = new BasicConfig(path.toString());
            boolean enabled = cfg.getBooleanProperty("enabled", false);
            String serverId = cfg.getStringProperty("serverId", "robin1");
            String jdbcUrl = cfg.getStringProperty("jdbcUrl", DEFAULT_JDBC_URL);
            String username = cfg.getStringProperty("username", "robin");
            String password = cfg.getStringProperty("password", "");
            String tableName = cfg.getStringProperty("tableName", "config");
            int maxPoolSize = Math.toIntExact(cfg.getLongProperty("maxPoolSize", 4L));

            if (serverId == null || serverId.isBlank()) {
                serverId = "robin1";
            }

            return Optional.of(new ConfigStoreConfig(enabled, serverId, jdbcUrl, username, password, tableName, maxPoolSize,
                    cfg.getBooleanProperty("required", false)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read configuration store bootstrap");
        }
    }
}
