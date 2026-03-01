package com.mimecast.robin.user.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies DKIM schema objects for PostgreSQL.
 */
public final class DkimSchema {

    private static final Logger log = LogManager.getLogger(DkimSchema.class);
    private static final String SCHEMA_RESOURCE = "user-schema-pg.sql";

    private DkimSchema() {
        // utility
    }

    /**
     * Applies the DKIM schema if the active datasource is PostgreSQL.
     *
     * @param dataSource shared datasource
     */
    public static void apply(HikariDataSource dataSource) {
        if (dataSource == null) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            if (!isPostgreSql(connection)) {
                log.debug("Skipping DKIM schema migration for non-PostgreSQL datasource");
                return;
            }

            String sql = loadSchemaSql();
            List<String> statements = splitStatements(sql);
            try (Statement statement = connection.createStatement()) {
                for (String part : statements) {
                    statement.execute(part);
                }
            }
            log.info("Applied DKIM PostgreSQL schema ({} statements)", statements.size());
        } catch (Exception e) {
            log.warn("Failed to apply DKIM schema migration: {}", e.getMessage());
        }
    }

    private static boolean isPostgreSql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String url = connection.getMetaData().getURL();
        String combined = (product + " " + url).toLowerCase(Locale.ROOT);
        return combined.contains("postgres");
    }

    private static String loadSchemaSql() throws IOException {
        try (InputStream input = DkimSchema.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing schema resource: " + SCHEMA_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (!inSingleQuote && !inDoubleQuote && c == '-' && next == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }
}
