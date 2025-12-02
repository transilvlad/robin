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
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("SqlUserLookupPool");
        this.ds = new HikariDataSource(cfg);
        this.userQuery = userQuery;
    }

    public SqlUserLookup(HikariDataSource ds, String userQuery) {
        this.ds = ds;
        this.userQuery = userQuery;
    }

    public SqlUserLookup(com.zaxxer.hikari.HikariDataSource sharedDs, String userQuery, boolean unused) {
        this.ds = sharedDs;
        this.userQuery = userQuery;
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

    public void close() {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("Error closing DataSource: {}", e.getMessage());
        }
    }
}
