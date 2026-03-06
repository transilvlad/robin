package com.mimecast.robin.scanners;

import com.mimecast.robin.config.server.RspamdConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DkimSigningLookup.
 * <p>
 * Uses the package-private constructor to inject a mock datasource, avoiding
 * the need for a real database in unit tests.
 * <p>
 * Runs sequentially because tests share the static singleton field.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DkimSigningLookupTest {

    @AfterEach
    void resetSingleton() throws Exception {
        Field field = DkimSigningLookup.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static RspamdConfig.DkimSigningConfig buildConfig(boolean enabled) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("jdbcUrl", "jdbc:fake://localhost/robin");
        map.put("user", "robin");
        map.put("password", "robin");
        map.put("signingQuery", "SELECT domain, selector FROM dkim_signing WHERE sender_domain = ?");
        return new RspamdConfig.DkimSigningConfig(map);
    }

    /**
     * Mock datasource that returns a configurable set of rows.
     */
    private static class MockLookup extends DkimSigningLookup {

        private final List<String[]> rows;

        MockLookup(List<String[]> rows) {
            super(null, "SELECT domain, selector FROM dkim_signing WHERE sender_domain = ?");
            this.rows = rows;
        }

        @Override
        public List<String[]> lookup(String domain) {
            return rows;
        }
    }

    @Test
    void testLookup_returnsMultipleRows() throws Exception {
        List<String[]> expected = List.of(
                new String[]{"example.com", "default"},
                new String[]{"esp.net", "2024q1"}
        );
        DkimSigningLookup lookup = new MockLookup(expected);

        List<String[]> result = lookup.lookup("example.com");

        assertEquals(2, result.size(), "Should return two signing options");
        assertArrayEquals(new String[]{"example.com", "default"}, result.get(0));
        assertArrayEquals(new String[]{"esp.net", "2024q1"}, result.get(1));
    }

    @Test
    void testLookup_returnsEmptyList() throws Exception {
        DkimSigningLookup lookup = new MockLookup(List.of());

        List<String[]> result = lookup.lookup("unknown.com");

        assertTrue(result.isEmpty(), "Should return empty list when no matching rows");
    }

    @Test
    void testGetInstance_returnsSameInstance() throws Exception {
        // Inject an instance directly to avoid real DB connection.
        DkimSigningLookup first = new MockLookup(List.of());
        Field field = DkimSigningLookup.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, first);

        DkimSigningLookup second = DkimSigningLookup.getInstance(buildConfig(true));
        assertSame(first, second, "getInstance should return the existing singleton");
    }

    @Test
    void testClose_clearsInstance() throws Exception {
        Field field = DkimSigningLookup.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, new MockLookup(List.of()));

        DkimSigningLookup.close();

        assertNull(field.get(null), "Singleton should be null after close");
    }

    @Test
    void testClose_isIdempotent() {
        // Should not throw even when no instance exists.
        assertDoesNotThrow(DkimSigningLookup::close);
        assertDoesNotThrow(DkimSigningLookup::close);
    }
}
