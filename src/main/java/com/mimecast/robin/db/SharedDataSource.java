package com.mimecast.robin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SharedDataSource provides a lazily-initialized HikariDataSource singleton based on
 * the Dovecot SQL configuration in `cfg/dovecot.json5` (authSql section).
 *
 * Usage: call SharedDataSource.getDataSource() to obtain the shared HikariDataSource.
 * Call SharedDataSource.close() on shutdown to release resources.
 */
public final class SharedDataSource {
    private static final Logger log = LogManager.getLogger(SharedDataSource.class);
    private static final Map<String, HikariDataSource> DATA_SOURCES = new LinkedHashMap<>();
    private static final String DEFAULT_POOL = "RobinSharedPool";

    private SharedDataSource() {
        // static utility
    }

    public static synchronized HikariDataSource getDataSource() {
        var dovecot = Config.getServer().getDovecot();
        return getDataSource(
                DEFAULT_POOL,
                dovecot.getAuthSqlJdbcUrl(),
                dovecot.getAuthSqlUser(),
                dovecot.getAuthSqlPassword(),
                8
        );
    }

    /**
     * Gets or creates a named shared Hikari pool.
     */
    public static synchronized HikariDataSource getDataSource(String poolName, String jdbcUrl,
                                                               String user, String password, int maxPoolSize) {
        String name = poolName == null || poolName.isBlank() ? DEFAULT_POOL : poolName;
        HikariDataSource existing = DATA_SOURCES.get(name);
        if (existing != null) {
            return existing;
        }

        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl);
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(Math.max(1, maxPoolSize));
            cfg.setPoolName(name);

            HikariDataSource dataSource = new HikariDataSource(cfg);
            DATA_SOURCES.put(name, dataSource);
            log.info("Initialized shared HikariDataSource: pool={}, jdbcUrl={}", name, jdbcUrl);
            return dataSource;
        } catch (Exception e) {
            log.error("Failed to initialize shared datasource {}: {}", name, e.getMessage());
            throw e;
        }
    }

    public static synchronized void close() {
        for (Map.Entry<String, HikariDataSource> entry : DATA_SOURCES.entrySet()) {
            try {
                entry.getValue().close();
                log.info("Closed shared HikariDataSource: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("Error closing shared DataSource {}: {}", entry.getKey(), e.getMessage());
            }
        }
        DATA_SOURCES.clear();
    }
}
