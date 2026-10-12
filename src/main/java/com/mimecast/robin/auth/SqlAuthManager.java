package com.mimecast.robin.auth;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.sasl.SqlAuthProvider;
import com.mimecast.robin.sasl.SqlUserLookup;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;

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
            if (authQuery == null || authQuery.isBlank()) {
                authQuery = SqlAuthProvider.DEFAULT_AUTH_QUERY;
            }
            String userQuery = dovecot.getAuthSqlUserQuery();
            String domainQuery = dovecot.getAuthSqlDomainQuery();
            String aliasQuery = dovecot.getAuthSqlAliasQuery();
            if (dovecot.isUserLookup() && (userQuery == null || userQuery.isBlank())) {
                throw new SQLException("SQL user lookup requires a non-empty userQuery");
            }
            validateQuery(ds, authQuery);
            if (dovecot.isUserLookup()) {
                validateQuery(ds, userQuery);
                validateQuery(ds, domainQuery);
                validateQuery(ds, aliasQuery);
            }
            SqlAuthProvider provider = new SqlAuthProvider(ds, authQuery);
            SqlUserLookup lookup = new SqlUserLookup(ds, userQuery, domainQuery, aliasQuery);
            authProvider = provider;
            userLookup = lookup;
            log.info("SqlAuthManager initialized with shared datasource");
        } catch (SQLException e) {
            log.error("Failed to initialize SqlAuthManager: {}", e.getMessage());
            throw new IllegalStateException("Required SQL authentication queries are unavailable", e);
        }
    }

    private static void validateQuery(HikariDataSource ds, String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return;
        }
        try (var connection = ds.getConnection();
             var statement = connection.prepareStatement(query)) {
            int parameters = statement.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= parameters; i++) {
                statement.setString(i, null);
            }
            statement.setMaxRows(1);
            try (var result = statement.executeQuery()) {
                result.getMetaData();
            }
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
