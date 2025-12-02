package com.mimecast.robin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SharedDataSource provides a lazily-initialized HikariDataSource singleton based on
 * the Dovecot SQL configuration in `cfg/dovecot.json5` (authSql section).
 *
 * Usage: call SharedDataSource.getDataSource() to obtain the shared HikariDataSource.
 * Call SharedDataSource.close() on shutdown to release resources.
 */
public final class SharedDataSource {
    private static final Logger log = LogManager.getLogger(SharedDataSource.class);
    private static volatile HikariDataSource ds;

    private SharedDataSource() {
        // static utility
    }

    public static synchronized HikariDataSource getDataSource() {
        if (ds == null) {
            try {
                var dovecot = Config.getServer().getDovecot();
                String jdbcUrl = dovecot.getAuthSqlJdbcUrl();
                String user = dovecot.getAuthSqlUser();
                String password = dovecot.getAuthSqlPassword();

                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername(user);
                cfg.setPassword(password);
                cfg.setMaximumPoolSize(8);
                cfg.setPoolName("RobinSharedPool");

                ds = new HikariDataSource(cfg);
                log.info("Initialized shared HikariDataSource for SQL auth: {}", jdbcUrl);
            } catch (Exception e) {
                log.error("Failed to initialize shared datasource: {}", e.getMessage());
                throw e;
            }
        }
        return ds;
    }

    public static synchronized void close() {
        if (ds != null) {
            try {
                ds.close();
                log.info("Closed shared HikariDataSource");
            } catch (Exception e) {
                log.warn("Error closing shared DataSource: {}", e.getMessage());
            } finally {
                ds = null;
            }
        }
    }
}
