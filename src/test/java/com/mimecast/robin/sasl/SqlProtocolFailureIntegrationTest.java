package com.mimecast.robin.sasl;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.extension.server.ServerAuth;
import com.mimecast.robin.smtp.extension.server.ServerRcpt;
import com.mimecast.robin.smtp.verb.Verb;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Tag("suite")
@Isolated
class SqlProtocolFailureIntegrationTest {
    private Map<String, Object> originalConfig;

    @BeforeEach
    void setup() throws Exception {
        Foundation.init("src/test/resources/cfg/");
        SqlAuthManager.close();
        originalConfig = new HashMap<>(Config.getServer().getMap());
        Config.getServer().getMap().put("dovecot", new HashMap<>(Map.of("auth", true, "userLookup", true,
                "authSql", new HashMap<>(Map.of("enabled", true,
                        "passwordQuery", "SELECT '{PLAIN}test' WHERE CAST(? AS TEXT) IS NOT NULL",
                        "userQuery", "SELECT '/mail' AS home, 5000 AS uid, 5000 AS gid, 'maildir:/mail' AS mail WHERE CAST(? AS TEXT) IS NOT NULL",
                        "domainQuery", "", "aliasQuery", "")))));
    }

    @AfterEach
    void cleanup() throws Exception {
        SqlAuthManager.close();
        Config.getServer().getMap().clear();
        Config.getServer().getMap().putAll(originalConfig);
    }

    private HikariDataSource dataSource() {
        String jdbcUrl = System.getProperty("robin.integration.jdbcUrl");
        assertNotNull(jdbcUrl, "Supply the isolated integration database URL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("robin");
        config.setPassword("robin");
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "domain", "alias", "user"})
    void backendOutagesReturn451WithoutPermanentFailure(String stage) throws Exception {
        var sql = Config.getServer().getDovecot().getMapProperty("authSql");
        if ("domain".equals(stage)) {
            sql.put("domainQuery", "SELECT 1 WHERE CAST(? AS TEXT) IS NOT NULL");
        } else if ("alias".equals(stage)) {
            sql.put("aliasQuery", "SELECT 'user@example.test' WHERE CAST(? AS TEXT) IS NOT NULL");
        }
        try (var source = dataSource()) {
            SqlAuthManager.init(source);
            source.close();
            ConnectionMock connection = new ConnectionMock(new StringBuilder("AHVzZXJAZXhhbXBsZS50ZXN0AHRlc3Q=\r\n"));
            try {
                if ("auth".equals(stage)) {
                    connection.getSession().setSecurePort(true);
                    connection.getSession().setStartTls(true);
                    assertFalse(new ServerAuth().process(connection, new Verb("AUTH PLAIN")));
                    assertFalse(connection.getSession().isAuth());
                } else {
                    connection.getSession().addEnvelope(new MessageEnvelope());
                    assertFalse(new ServerRcpt().process(connection, new Verb("RCPT TO: <user@example.test>")));
                    assertTrue(connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty());
                }
                assertEquals(1, connection.getOutput().lines().filter(line -> line.startsWith("451")).count());
                assertFalse(connection.getOutput().lines().anyMatch(line -> line.startsWith("535") || line.startsWith("550")));
            } finally {
                connection.getSession().close();
            }
        }
    }

    @Test
    void reachableDatabaseKeepsNormalCredentialAndUnknownMailboxResults() throws Exception {
        try (var source = dataSource()) {
            SqlAuthManager.init(source);
            var auth = SqlAuthManager.getAuthProvider();
            assertTrue(auth.authenticate("user@example.test", "test"));
            assertFalse(auth.authenticate("user@example.test", "wrong"));
            try (var lookup = new SqlUserLookup(source,
                    "SELECT '/mail' AS home, 5000 AS uid, 5000 AS gid, 'maildir:/mail' AS mail WHERE CAST(? AS TEXT) IS NULL",
                    "SELECT 1 WHERE CAST(? AS TEXT) IS NULL", "SELECT 'user@example.test' WHERE CAST(? AS TEXT) IS NULL")) {
                assertTrue(lookup.lookup("unknown@example.test").isEmpty());
                assertFalse(lookup.isDomainServed("unknown.test"));
                assertTrue(lookup.resolveAlias("unknown@example.test").isEmpty());
            }
        }
    }

    @Test
    void invalidRequiredQueryFailsBeforeProvidersArePublished() throws Exception {
        Config.getServer().getDovecot().getMapProperty("authSql")
                .put("userQuery", "SELECT missing_column FROM users WHERE email = ?");
        try (var source = dataSource()) {
            assertThrows(IllegalStateException.class, () -> SqlAuthManager.init(source));
            assertNull(SqlAuthManager.getAuthProvider());
            assertNull(SqlAuthManager.getUserLookup());
        }
    }
}
