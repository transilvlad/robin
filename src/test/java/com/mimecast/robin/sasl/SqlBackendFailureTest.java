package com.mimecast.robin.sasl;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class SqlBackendFailureTest {
    private Map<String, Object> originalConfig;

    @BeforeEach
    void setup() throws Exception {
        Foundation.init("src/test/resources/cfg/");
        originalConfig = new HashMap<>(Config.getServer().getMap());
        Config.getServer().getMap().put("dovecot", new HashMap<>(Map.of(
                "userLookup", false, "authSql", Map.of("passwordQuery", "SELECT password FROM users WHERE email=?"))));
        SqlAuthManager.close();
    }

    @AfterEach
    void cleanup() {
        SqlAuthManager.close();
        Config.getServer().getMap().clear();
        Config.getServer().getMap().putAll(originalConfig);
    }

    private HikariDataSource unavailableDataSource() {
        return new HikariDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("test database unavailable", "08006");
            }
        };
    }

    @Test
    void authOutageIsNotInvalidCredentials() {
        try (var source = unavailableDataSource();
             var auth = new SqlAuthProvider(source, null)) {
            var failure = assertThrows(IllegalStateException.class, () -> auth.authenticate("user@example.test", "test"));
            assertInstanceOf(SQLException.class, failure.getCause());
        }
    }

    @Test
    void directoryOutageIsNotAnUnknownUserDomainOrAlias() {
        try (var source = unavailableDataSource();
             var lookup = new SqlUserLookup(source, "SELECT home,uid,gid,mail FROM users WHERE email=?",
                     "SELECT 1 FROM users WHERE email LIKE ?", "SELECT destination FROM aliases WHERE source=?")) {
            assertThrows(IllegalStateException.class, () -> lookup.lookup("user@example.test"));
            assertThrows(IllegalStateException.class, () -> lookup.isDomainServed("example.test"));
            assertThrows(IllegalStateException.class, () -> lookup.resolveAlias("alias@example.test"));
        }
    }

    @Test
    void startupFailsWithoutPublishingPartiallyInitializedProviders() throws Exception {
        Foundation.init("src/test/resources/cfg/");
        SqlAuthManager.close();
        try (var source = unavailableDataSource()) {
            assertThrows(IllegalStateException.class, () -> SqlAuthManager.init(source));
            assertNull(SqlAuthManager.getAuthProvider());
            assertNull(SqlAuthManager.getUserLookup());
        } finally {
            SqlAuthManager.close();
        }
    }

    @Test
    void startupValidatesTheDefaultPasswordQuery() throws Exception {
        Config.getServer().getDovecot().getMap().put("authSql", Map.of("passwordQuery", ""));
        SqlAuthManager.close();
        try (var source = unavailableDataSource()) {
            assertThrows(IllegalStateException.class, () -> SqlAuthManager.init(source));
            assertNull(SqlAuthManager.getAuthProvider());
        } finally {
            SqlAuthManager.close();
        }
    }

    @Test
    void enabledUserLookupRequiresAQuery() {
        Config.getServer().getDovecot().getMap().put("userLookup", true);
        try (var source = new HikariDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("Empty required query must fail before database access");
            }
        }) {
            Config.getServer().getDovecot().getMap().put("authSql", Map.of("passwordQuery", "", "userQuery", ""));
            assertThrows(IllegalStateException.class, () -> SqlAuthManager.init(source));
        }
    }
}
