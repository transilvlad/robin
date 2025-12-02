package com.mimecast.robin.auth;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.SqlAuthProvider;
import com.mimecast.robin.sasl.SqlUserLookup;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SqlAuthManager holds shared instances of SqlAuthProvider and SqlUserLookup
 * backed by the shared HikariDataSource. This avoids creating pools per request
 * and centralizes lifecycle management.
 */
public final class SqlAuthManager {
    private static final Logger log = LogManager.getLogger(SqlAuthManager.class);

    private static volatile SqlAuthProvider authProvider;
    private static volatile SqlUserLookup userLookup;

    private SqlAuthManager() {
        // static utility
    }

    public static synchronized void init(HikariDataSource ds) {
        if (authProvider != null || userLookup != null) return;
        try {
            var dovecot = Config.getServer().getDovecot();
            String authQuery = dovecot.getAuthSqlPasswordQuery();
            String userQuery = dovecot.getAuthSqlUserQuery();
            authProvider = new SqlAuthProvider(ds, authQuery);
            userLookup = new SqlUserLookup(ds, userQuery);
            log.info("SqlAuthManager initialized with shared datasource");
        } catch (Exception e) {
            log.error("Failed to initialize SqlAuthManager: {}", e.getMessage());
            // propagate or leave null — callers must handle null
        }
    }

    public static SqlAuthProvider getAuthProvider() {
        return authProvider;
    }

    public static SqlUserLookup getUserLookup() {
        return userLookup;
    }

    public static synchronized void close() {
        try {
            if (authProvider != null) {
                authProvider.close();
                authProvider = null;
            }
        } catch (Exception e) {
            log.warn("Error closing SqlAuthProvider: {}", e.getMessage());
        }
        try {
            if (userLookup != null) {
                userLookup.close();
                userLookup = null;
            }
        } catch (Exception e) {
            log.warn("Error closing SqlUserLookup: {}", e.getMessage());
        }
        log.info("SqlAuthManager closed");
    }
}
