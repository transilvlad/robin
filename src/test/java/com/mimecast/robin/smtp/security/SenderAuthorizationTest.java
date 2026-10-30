package com.mimecast.robin.smtp.security;

import com.mimecast.robin.mime.EmailParser;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SenderAuthorizationTest {
    private final RecordingDataSource source = new RecordingDataSource();
    private final SenderAuthorization authorization = new SenderAuthorization(source, SenderAuthorization.DEFAULT_QUERY);

    @Test
    void requiresAnExplicitGrantAndBindsNormalizedExactAccountAndAddress() throws Exception {
        source.results.addAll(List.of(true, false));
        assertTrue(authorization.isAuthorized(" Alice@Example.COM ", "Delegated@Example.com"));
        assertFalse(authorization.isAuthorized("alice@example.com", "ungranted@example.com"));
        assertEquals(List.of("alice@example.com", "delegated@example.com",
                "alice@example.com", "ungranted@example.com"), source.bound);
        assertEquals(List.of(5, 5), source.timeouts);
        assertTrue(SenderAuthorization.DEFAULT_QUERY.contains("JOIN users"));
        assertTrue(SenderAuthorization.DEFAULT_QUERY.contains("d.enabled=true"));
    }

    @Test
    void nullReversePathHasNoImplicitSubmissionGrant() throws Exception {
        assertFalse(authorization.isAuthorized("alice@example.com", ""));
        assertEquals(0, source.connections);
    }

    @Test
    void everySubmittedAuthorAndSenderMustBeExplicitlyAuthorized() throws Exception {
        source.results.addAll(List.of(true, false));
        try (var parser = parse("From: Alice <alice@example.com>\r\nSender: foreign@example.com\r\n\r\nbody")) {
            assertFalse(authorization.isHeaderAuthorized("alice@example.com", parser));
        }
        assertEquals(List.of("alice@example.com", "alice@example.com",
                "alice@example.com", "foreign@example.com"), source.bound);
    }

    @Test
    void ambiguousOrMissingFromDoesNotBypassAuthorization() throws Exception {
        for (String headers : new String[]{"Subject: absent", "From: a@example.com\r\nFrom: b@example.com",
                "From: a@example.com,b@example.com", "From: group: a@example.com;"}) {
            try (var parser = parse(headers + "\r\n\r\nbody")) {
                assertFalse(authorization.isHeaderAuthorized("alice@example.com", parser));
            }
        }
        assertEquals(0, source.connections);
    }

    @Test
    void sqlFailureIsNotConvertedToPermissionOrMissingMailbox() throws Exception {
        source.failure = true;
        assertThrows(SQLException.class, () -> authorization.isAuthorized("a@example.com", "a@example.com"));
    }

    private EmailParser parse(String message) throws Exception {
        return new EmailParser(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8))).parse(true);
    }

    private static final class RecordingDataSource extends HikariDataSource {
        private final List<String> bound = new ArrayList<>();
        private final List<Integer> timeouts = new ArrayList<>();
        private final Deque<Boolean> results = new ArrayDeque<>();
        private int connections;
        private boolean failure;

        @Override
        public Connection getConnection() {
            connections++;
            return proxy(Connection.class, (method, arguments) -> {
                if ("prepareStatement".equals(method)) {
                    assertEquals(SenderAuthorization.DEFAULT_QUERY, arguments[0]);
                    return statement();
                }
                return null;
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (method, arguments) -> switch (method) {
                case "setString" -> {
                    bound.add((String) arguments[1]);
                    yield null;
                }
                case "setQueryTimeout" -> {
                    timeouts.add((Integer) arguments[0]);
                    yield null;
                }
                case "executeQuery" -> {
                    if (failure) {
                        throw new SQLException("synthetic outage");
                    }
                    boolean granted = !results.isEmpty() && results.removeFirst();
                    yield proxy(ResultSet.class, (name, args) -> "next".equals(name) ? granted : null);
                }
                default -> null;
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Handler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (instance, method, arguments) -> handler.invoke(method.getName(), arguments));
        }

        private interface Handler {
            Object invoke(String method, Object[] arguments) throws SQLException;
        }
    }
}
