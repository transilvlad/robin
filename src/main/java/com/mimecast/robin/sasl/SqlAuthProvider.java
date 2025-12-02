package com.mimecast.robin.sasl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SqlAuthProvider verifies credentials against the users table using Postgres' pgcrypto crypt()
 * function. It runs a query which compares crypt(plain, stored_hash) = stored_hash. This avoids
 * implementing crypt verification in Java and relies on the database to do the check.
 *
 * Requirements: pgcrypto extension must be available in the database.
 */
public class SqlAuthProvider implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(SqlAuthProvider.class);

    private final HikariDataSource ds;
    private final String authQuery;

    /**
     * Default authQuery expects two parameters: passwordAttempt, email
     * and returns a single boolean column named ok.
     */
    // Strip any leading {SCHEME} prefix (e.g. {SHA512-CRYPT}) before using crypt()
    public static final String DEFAULT_AUTH_QUERY =
            "SELECT (crypt(?, regexp_replace(password, '^\\{[^}]+\\}', '')) = regexp_replace(password, '^\\{[^}]+\\}', '')) AS ok FROM users WHERE email = ?";

    public SqlAuthProvider(String jdbcUrl, String user, String password, String authQuery) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("SqlAuthProviderPool");
        this.ds = new HikariDataSource(cfg);
        this.authQuery = authQuery == null || authQuery.isBlank() ? DEFAULT_AUTH_QUERY : authQuery;
    }

    public SqlAuthProvider(HikariDataSource ds, String authQuery) {
        this.ds = ds;
        this.authQuery = authQuery == null || authQuery.isBlank() ? DEFAULT_AUTH_QUERY : authQuery;
    }

    public boolean authenticate(String email, String plainPassword) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(authQuery)) {
            ps.setString(1, plainPassword);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("ok");
                }
            }
        } catch (SQLException e) {
            log.error("SQL auth failed for {}: {}", email, e.getMessage());
        }
        return false;
    }

    public void close() {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("Error closing DataSource: {}", e.getMessage());
        }
    }
}
