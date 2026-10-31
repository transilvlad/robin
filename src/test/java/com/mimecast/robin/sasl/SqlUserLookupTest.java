package com.mimecast.robin.sasl;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that SqlUserLookup matches addresses case-insensitively.
 */
class SqlUserLookupTest {

    @Test
    void normalizeLowerCasesAndTrims() {
        assertEquals("tony@example.com", SqlUserLookup.normalize(" Tony@Example.COM "));
        assertNull(SqlUserLookup.normalize(null));
    }

    @Test
    void lookupsBindLowerCaseAddresses() {
        RecordingDataSource ds = new RecordingDataSource();
        SqlUserLookup lookup = new SqlUserLookup(ds,
                "SELECT home, uid, gid, maildir AS mail FROM users WHERE email = ?",
                "SELECT 1 FROM users WHERE email LIKE '%@' || ?",
                "SELECT destination FROM aliases WHERE source = ? UNION SELECT email FROM users WHERE email = ?");

        lookup.lookup("Tony@Example.com");
        lookup.isDomainServed("Example.COM");
        lookup.resolveAlias("TONY-Alias@example.com");

        assertEquals(List.of("tony@example.com", "example.com", "tony-alias@example.com", "tony-alias@example.com"), ds.bound);
    }

    @Test
    void canonicalAliasQueryReadsEveryRowInsteadOfTheLegacyFirstDestination() {
        RecordingDataSource ds = new RecordingDataSource();
        ds.aliases = List.of("alice@example.com", "bob@example.com");
        SqlUserLookup lookup = new SqlUserLookup(ds, "", "", "SELECT destination FROM aliases LIMIT 1");
        assertEquals(ds.aliases, lookup.findAliases("TEAM@EXAMPLE.COM"));
        assertEquals(List.of("team@example.com"), ds.bound);
    }

    /**
     * Data source whose statements record bound parameters and return no rows.
     */
    private static final class RecordingDataSource extends HikariDataSource {
        private final List<String> bound = new ArrayList<>();
        private List<String> aliases = List.of();

        @Override
        public Connection getConnection() {
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "prepareStatement" -> statement((String) args[0], countPlaceholders((String) args[0]));
                default -> null;
            });
        }

        private PreparedStatement statement(String sql, int parameters) {
            int[] row = {-1};
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setString" -> bound.add((String) args[1]);
                case "getParameterMetaData" -> proxy(ParameterMetaData.class, (m, a) -> "getParameterCount".equals(m) ? parameters : null);
                case "executeQuery" -> proxy(ResultSet.class, (m, a) -> switch (m) {
                    case "next" -> sql.equals(RecipientDirectory.ALIAS_QUERY) && ++row[0] < aliases.size();
                    case "getString" -> aliases.get(row[0]);
                    default -> null;
                });
                default -> null;
            });
        }

        private static int countPlaceholders(String sql) {
            return (int) sql.chars().filter(c -> c == '?').count();
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Handler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (p, method, args) -> handler.handle(method.getName(), args));
        }

        private interface Handler {
            Object handle(String method, Object[] args);
        }
    }
}
