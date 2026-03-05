package com.mimecast.robin.sasl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * SqlUserLookup provides user existence lookup against a configured SQL database.
 * It is intended as an alternative to the Dovecot UNIX domain socket userdb lookup.
 *
 * Usage: construct with a JDBC URL/user/password or provide a HikariDataSource.
 */
public class SqlUserLookup implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(SqlUserLookup.class);

    private final HikariDataSource ds;
    private final String userQuery;
    private final String domainQuery;
    private final String aliasQuery;

    public static class UserRecord {
        public final String email;
        public final String home;
        public final int uid;
        public final int gid;
        public final String maildir;

        public UserRecord(String email, String home, int uid, int gid, String maildir) {
            this.email = email;
            this.home = home;
            this.uid = uid;
            this.gid = gid;
            this.maildir = maildir;
        }
    }

    public SqlUserLookup(String jdbcUrl, String user, String password, String userQuery) {
        this(jdbcUrl, user, password, userQuery, null, null);
    }

    public SqlUserLookup(String jdbcUrl, String user, String password, String userQuery,
                          String domainQuery, String aliasQuery) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("SqlUserLookupPool");
        this.ds = new HikariDataSource(cfg);
        this.userQuery = userQuery;
        this.domainQuery = domainQuery;
        this.aliasQuery = aliasQuery;
    }

    public SqlUserLookup(HikariDataSource ds, String userQuery) {
        this(ds, userQuery, null, null);
    }

    public SqlUserLookup(HikariDataSource ds, String userQuery, String domainQuery, String aliasQuery) {
        this.ds = ds;
        this.userQuery = userQuery;
        this.domainQuery = domainQuery;
        this.aliasQuery = aliasQuery;
    }

    public SqlUserLookup(com.zaxxer.hikari.HikariDataSource sharedDs, String userQuery, boolean unused) {
        this.ds = sharedDs;
        this.userQuery = userQuery;
        this.domainQuery = null;
        this.aliasQuery = null;
    }

    public Optional<UserRecord> lookup(String email) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(userQuery)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String home = rs.getString("home");
                    int uid = rs.getInt("uid");
                    int gid = rs.getInt("gid");
                    String mail = rs.getString("mail");
                    return Optional.of(new UserRecord(email, home, uid, gid, mail));
                }
            }
        } catch (SQLException e) {
            log.error("SQL user lookup failed for {}: {}", email, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Checks if a domain is served by this mail system.
     * Returns true if domainQuery is not configured (backward compatible).
     *
     * @param domain Domain to check (e.g. "example.com").
     * @return True if the domain is served or no domain query is configured.
     */
    public boolean isDomainServed(String domain) {
        if (domainQuery == null || domainQuery.isEmpty()) {
            return true;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(domainQuery)) {
            // The domain query has '%@' || ? in SQL, so we pass just the domain.
            int paramCount = ps.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= paramCount; i++) {
                ps.setString(i, domain);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("SQL domain lookup failed for {}: {}", domain, e.getMessage());
            return false;
        }
    }

    /**
     * Resolves an email alias to its real destination address.
     * Returns empty if aliasQuery is not configured (backward compatible).
     *
     * @param email Email address to resolve.
     * @return Optional destination address, or empty if no alias exists.
     */
    public Optional<String> resolveAlias(String email) {
        if (aliasQuery == null || aliasQuery.isEmpty()) {
            return Optional.empty();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(aliasQuery)) {
            // The alias query uses UNION across multiple tables, each needing the email.
            int paramCount = ps.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= paramCount; i++) {
                ps.setString(i, email);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String destination = rs.getString(1);
                    if (destination != null && !destination.isEmpty()) {
                        return Optional.of(destination);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("SQL alias lookup failed for {}: {}", email, e.getMessage());
        }
        return Optional.empty();
    }

    public void close() {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("Error closing DataSource: {}", e.getMessage());
        }
    }
}
