package com.mimecast.robin.sasl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.codec.digest.Crypt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SqlAuthProvider verifies credentials against a users table.
 * <p>
 * Supports two query modes based on the number of query parameters:
 * <ul>
 *   <li><b>2 parameters (default):</b> Database-side verification using pgcrypto {@code crypt()}.
 *       Parameters: (plainPassword, email). Must return a boolean column named {@code ok}.
 *       Requires the pgcrypto extension in PostgreSQL.</li>
 *   <li><b>1 parameter:</b> Java-side verification. Parameter: (email). Must return the stored
 *       password hash in column 1. Supports {@code {PLAIN}}, {@code {SHA512-CRYPT}},
 *       and {@code {BLF-CRYPT}} Dovecot password schemes.</li>
 * </ul>
 */
public class SqlAuthProvider implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(SqlAuthProvider.class);

    private final HikariDataSource ds;
    private final String authQuery;
    private final int paramCount;

    /**
     * Default authQuery expects two parameters: passwordAttempt, email
     * and returns a single boolean column named ok.
     * Strips any leading {SCHEME} prefix (e.g. {SHA512-CRYPT}) before using crypt().
     */
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
        this.paramCount = countPlaceholders(this.authQuery);
    }

    public SqlAuthProvider(HikariDataSource ds, String authQuery) {
        this.ds = ds;
        this.authQuery = authQuery == null || authQuery.isBlank() ? DEFAULT_AUTH_QUERY : authQuery;
        this.paramCount = countPlaceholders(this.authQuery);
    }

    /**
     * Authenticates a user by email and plain-text password.
     *
     * @param email         User email address.
     * @param plainPassword Plain-text password attempt.
     * @return True if credentials match.
     */
    public boolean authenticate(String email, String plainPassword) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(authQuery)) {
            if (paramCount >= 2) {
                // DB-side verification (pgcrypto crypt comparison).
                ps.setString(1, plainPassword);
                ps.setString(2, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean("ok");
                }
            } else {
                // Java-side verification — query returns password hash.
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String storedHash = rs.getString(1);
                    return verifyPassword(plainPassword, storedHash);
                }
            }
        } catch (SQLException e) {
            log.error("SQL auth failed for {}: {}", email, e.getMessage());
        }
        return false;
    }

    /**
     * Verifies a plain-text password against a stored hash with Dovecot scheme detection.
     * Supports {PLAIN}, {SHA512-CRYPT}, and {BLF-CRYPT} schemes.
     *
     * @param plainPassword Plain-text password attempt.
     * @param storedHash    Stored hash, optionally prefixed with {SCHEME}.
     * @return True if the password matches.
     */
    static boolean verifyPassword(String plainPassword, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return false;

        // Strip {SCHEME} prefix if present.
        String scheme = "";
        String hash = storedHash;
        if (storedHash.startsWith("{")) {
            int end = storedHash.indexOf('}');
            if (end > 0) {
                scheme = storedHash.substring(1, end).toUpperCase();
                hash = storedHash.substring(end + 1);
            }
        }

        return switch (scheme) {
            case "PLAIN", "" -> plainPassword.equals(hash);
            case "SHA512-CRYPT", "BLF-CRYPT" -> verifyCrypt(plainPassword, hash);
            default -> {
                log.warn("Unsupported password scheme: {}", scheme);
                yield false;
            }
        };
    }

    /**
     * Verifies a password using Unix crypt (supports $6$ SHA-512 and $2y$ bcrypt).
     */
    private static boolean verifyCrypt(String plainPassword, String hash) {
        try {
            String computed = Crypt.crypt(plainPassword, hash);
            return computed.equals(hash);
        } catch (Exception e) {
            log.error("Crypt verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static int countPlaceholders(String query) {
        int count = 0;
        for (char c : query.toCharArray()) {
            if (c == '?') count++;
        }
        return count;
    }

    public void close() {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("Error closing DataSource: {}", e.getMessage());
        }
    }
}
