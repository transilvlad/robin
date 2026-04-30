package com.mimecast.robin.config.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.PathUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Synchronizes Robin's local configuration directory from a PostgreSQL-backed JSON store.
 *
 * <p>Scope and safety rules:
 * <ul>
 *     <li>Only files ending in {@code .json5} are considered.</li>
 *     <li>{@code config.json5} is excluded to avoid bootstrapping loops.</li>
 *     <li>Filenames are validated to prevent path traversal; subdirectories are not supported.</li>
 *     <li>Critical files are protected from being overwritten by empty payloads.</li>
 * </ul>
 *
 * <p>Database schema (created automatically if missing):
 * <ul>
 *     <li>{@code server_id} (part of PK)</li>
 *     <li>{@code file_name} (part of PK)</li>
 *     <li>{@code payload_json} (JSONB)</li>
 *     <li>{@code updated_epoch} (seconds since epoch)</li>
 *     <li>{@code sha256} (hex string of canonical JSON payload)</li>
 * </ul>
 *
 * <p>Bootstrap behavior:
 * <ul>
 *     <li>If the table is empty, the store is seeded from local {@code *.json5} files.</li>
 *     <li>Once non-empty, the database is treated as authoritative and overwrites local files.
 *         Local files not present in the database are never deleted.</li>
 * </ul>
 */
public final class ConfigStoreSyncManager {
    private static final Logger log = LogManager.getLogger(ConfigStoreSyncManager.class);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+\\.json5$");

    private static final String DEFAULT_SERVER_ID = "default";

    private static final Set<String> CRITICAL_FILES = Set.of(
            "server.json5",
            "properties.json5",
            "client.json5"
    );

    private static volatile String configDir;
    private static volatile ConfigStoreSyncStatus status = ConfigStoreSyncStatus.disabled();

    private ConfigStoreSyncManager() {
        throw new IllegalStateException("Static class");
    }

    /**
     * Sets the configuration directory used when resolving {@code config.json5} and writing synced files.
     *
     * @param dir Configuration directory.
     */
    public static void setConfigDir(String dir) {
        configDir = dir;
    }

    /**
     * Returns the last known synchronization status.
     *
     * @return Sync status.
     */
    public static ConfigStoreSyncStatus getStatus() {
        return status;
    }

    /**
     * Runs a single synchronization pass if the feature is enabled in {@code config.json5}.
     *
     * <p>Failures are recorded in {@link #getStatus()} and the caller can decide whether to fail-open.
     */
    public static synchronized void syncIfEnabled() {
        String dir = configDir;
        Optional<ConfigStoreConfig> cfgOpt = ConfigStoreConfig.load(dir);
        if (cfgOpt.isEmpty() || !cfgOpt.get().enabled()) {
            status = ConfigStoreSyncStatus.disabled();
            return;
        }

        ConfigStoreConfig cfg = cfgOpt.get();

        long attemptMillis = System.currentTimeMillis();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        try {
            validateTableName(cfg.tableName());

            log.info("Config store sync enabled: table={}, serverId={}", cfg.tableName(), cfg.serverId());

            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                throw new IOException("Config directory does not exist: " + root);
            }

            try (HikariDataSource ds = createDataSource(cfg);
                 Connection c = ds.getConnection()) {
                ensureSchema(c, cfg.tableName());

                log.debug("Config store schema ensured: table={}", cfg.tableName());

                if (isServerEmpty(c, cfg.tableName(), DEFAULT_SERVER_ID)) {
                    log.info("Config store base is empty, seeding from local files: table={}, serverId={}", cfg.tableName(), DEFAULT_SERVER_ID);
                    seedFromLocal(root, c, cfg.tableName(), skipped);
                }

                downloadToDisk(root, c, cfg.tableName(), cfg.serverId(), applied, skipped);
            }

            log.info("Config store sync complete: applied={}, skipped={}, table={}, serverId={}", applied.size(), skipped.size(), cfg.tableName(), cfg.serverId());
            status = new ConfigStoreSyncStatus(true, attemptMillis, System.currentTimeMillis(), null, applied, skipped);
        } catch (Exception e) {
            log.error("Config store sync failed: {}", e.getMessage(), e);
            status = new ConfigStoreSyncStatus(true, attemptMillis, 0L, e.getMessage(), applied, skipped);
        }
    }

    private static HikariDataSource createDataSource(ConfigStoreConfig cfg) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(cfg.jdbcUrl());
        hikari.setUsername(cfg.username());
        hikari.setPassword(cfg.password());
        hikari.setMaximumPoolSize(cfg.maxPoolSize());
        hikari.setMinimumIdle(1);
        hikari.setPoolName("robin-config-store");
        return new HikariDataSource(hikari);
    }

    private static void ensureSchema(Connection c, String tableName) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "server_id VARCHAR(64) NOT NULL, "
                + "file_name VARCHAR(255) NOT NULL, "
                + "payload_json JSONB NOT NULL, "
                + "updated_epoch BIGINT NOT NULL, "
                + "sha256 VARCHAR(64) NOT NULL, "
                + "PRIMARY KEY (server_id, file_name)"
                + ")";

        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }

        migrateLegacySchemaIfRequired(c, tableName);
    }

    private static void migrateLegacySchemaIfRequired(Connection c, String tableName) throws SQLException {
        String normalizedTableName = tableName != null ? tableName.toLowerCase() : tableName;
        validateTableName(normalizedTableName);

        boolean originalAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);

            if (!hasColumn(c, normalizedTableName, "server_id")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + normalizedTableName + " ADD COLUMN server_id VARCHAR(64)");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + normalizedTableName + " SET server_id = ? WHERE server_id IS NULL")) {
                    ps.setString(1, DEFAULT_SERVER_ID);
                    ps.executeUpdate();
                }
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + normalizedTableName + " ALTER COLUMN server_id SET NOT NULL");
                }
            }

            if (!primaryKeyContainsServerId(c, normalizedTableName)) {
                String pkName = getPrimaryKeyConstraintName(c, normalizedTableName);
                try (Statement st = c.createStatement()) {
                    if (pkName != null) {
                        st.execute("ALTER TABLE " + normalizedTableName + " DROP CONSTRAINT \"" + pkName.replace("\"", "\"\"") + "\"");
                    }
                    st.execute("ALTER TABLE " + normalizedTableName + " ADD PRIMARY KEY (server_id, file_name)");
                }
            }

            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(originalAutoCommit);
        }
    }

    private static boolean hasColumn(Connection c, String tableName, String columnName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean primaryKeyContainsServerId(Connection c, String tableName) throws SQLException {
        String sql = "SELECT a.attname "
                + "FROM pg_constraint con "
                + "JOIN pg_class rel ON rel.oid = con.conrelid "
                + "JOIN pg_attribute a ON a.attrelid = rel.oid AND a.attnum = ANY(con.conkey) "
                + "WHERE rel.relname = ? AND con.contype = 'p'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("server_id".equalsIgnoreCase(rs.getString(1))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String getPrimaryKeyConstraintName(Connection c, String tableName) throws SQLException {
        String sql = "SELECT con.conname "
                + "FROM pg_constraint con "
                + "JOIN pg_class rel ON rel.oid = con.conrelid "
                + "WHERE rel.relname = ? AND con.contype = 'p'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean isServerEmpty(Connection c, String tableName, String serverId) throws SQLException {
        String sql = "SELECT 1 FROM " + tableName + " WHERE server_id = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private static void seedFromLocal(Path root, Connection c, String tableName, List<String> skipped) throws IOException, SQLException {
        if (!Files.isDirectory(root)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.json5")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (isExcluded(fileName)) {
                    skipped.add(fileName + " (excluded)");
                    continue;
                }

                JsonElement json = parseJson5File(file);
                if (isRejectedPayload(fileName, json)) {
                    skipped.add(fileName + " (invalid local json)");
                    continue;
                }

                upsert(c, tableName, DEFAULT_SERVER_ID, fileName, json);
            }
        }
    }

    private static void downloadToDisk(Path root,
                                       Connection c,
                                       String tableName,
                                       String serverId,
                                       List<String> applied,
                                       List<String> skipped) throws SQLException, IOException {
        if (serverId == null || serverId.isBlank()) {
            serverId = "robin1";
        }

        var base = readServerRows(c, tableName, DEFAULT_SERVER_ID);
        var overrides = readServerRows(c, tableName, serverId);

        for (Row row : base) {
            String fileName = row.fileName();
            JsonElement merged = row.json();
            Row overrideRow = overrides.stream().filter(r -> r.fileName().equals(fileName)).findFirst().orElse(null);
            if (overrideRow != null) {
                merged = mergeOverrideOnTop(row.json(), overrideRow.json());
            }

            if (isRejectedPayload(fileName, merged)) {
                skipped.add(fileName + " (rejected payload)");
                continue;
            }

            Path target = root.resolve(fileName);
            String canonical = GSON.toJson(merged);

            if (Files.exists(target)) {
                JsonElement existing = tryParseJson5File(target);
                if (existing != null && existing.equals(merged)) {
                    skipped.add(fileName + " (no change)");
                    continue;
                }
            }

            writeAtomically(target, canonical);
            applied.add(fileName);
        }

        for (Row overrideOnly : overrides) {
            boolean hasBase = base.stream().anyMatch(r -> r.fileName().equals(overrideOnly.fileName()));
            if (!hasBase) {
                skipped.add(overrideOnly.fileName() + " (override has no base)");
            }
        }
    }

    private record Row(String fileName, JsonElement json) {
    }

    private static List<Row> readServerRows(Connection c, String tableName, String serverId) throws SQLException {
        String sql = "SELECT file_name, payload_json::text AS payload_json FROM " + tableName + " WHERE server_id = ?";
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fileName = rs.getString("file_name");
                    String payloadText = rs.getString("payload_json");

                    if (!isAllowedFileName(fileName)) {
                        continue;
                    }
                    if (isExcluded(fileName)) {
                        continue;
                    }

                    JsonElement json;
                    try {
                        json = JsonParser.parseString(payloadText);
                    } catch (Exception e) {
                        continue;
                    }

                    rows.add(new Row(fileName, json));
                }
            }
        }
        return rows;
    }

    private static JsonElement mergeOverrideOnTop(JsonElement base, JsonElement override) {
        if (base == null || base.isJsonNull()) {
            return base;
        }
        if (override == null || override.isJsonNull()) {
            return base;
        }
        if (base.isJsonObject() && override.isJsonObject()) {
            var merged = base.getAsJsonObject().deepCopy();
            for (var entry : override.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (!merged.has(key)) {
                    log.debug("Skipping override key '{}' not present in base configuration", key);
                    continue;
                }

                JsonElement baseValue = merged.get(key);
                JsonElement overrideValue = entry.getValue();
                if (baseValue != null && baseValue.isJsonObject() && overrideValue != null && overrideValue.isJsonObject()) {
                    merged.add(key, mergeOverrideOnTop(baseValue, overrideValue));
                } else {
                    merged.add(key, overrideValue);
                }
            }
            return merged;
        }

        if (base.isJsonArray() && override.isJsonArray()) {
            return override;
        }

        return base;
    }

    private static boolean isExcluded(String fileName) {
        return "config.json5".equalsIgnoreCase(fileName);
    }

    private static boolean isAllowedFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        if (!fileName.endsWith(".json5")) {
            return false;
        }
        if (fileName.contains("/") || fileName.contains("\\")) {
            return false;
        }
        return SAFE_FILENAME.matcher(fileName).matches();
    }

    @SuppressWarnings("deprecation")
    private static JsonElement parseJson5File(Path file) throws IOException {
        String content = Magic.streamMagicReplace(PathUtils.readFile(file.toString(), StandardCharsets.UTF_8));
        try {
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true);
            return JsonParser.parseReader(reader);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonElement tryParseJson5File(Path file) {
        try {
            return parseJson5File(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isRejectedPayload(String fileName, JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return true;
        }

        if (!json.isJsonObject() && !json.isJsonArray()) {
            return true;
        }

        if (CRITICAL_FILES.contains(fileName)) {
            if (json.isJsonObject()) {
                return json.getAsJsonObject().isEmpty();
            }
            return json.getAsJsonArray().isEmpty();
        }

        return false;
    }

    private static void upsert(Connection c, String tableName, String serverId, String fileName, JsonElement json) throws SQLException {
        String payload = GSON.toJson(json);
        String sha = sha256(payload);
        long updatedEpoch = Instant.now().getEpochSecond();

        String sql = "INSERT INTO " + tableName + " (server_id, file_name, payload_json, updated_epoch, sha256) "
                + "VALUES (?, ?, ?::jsonb, ?, ?) "
                + "ON CONFLICT (server_id, file_name) DO UPDATE SET "
                + "payload_json = EXCLUDED.payload_json, "
                + "updated_epoch = EXCLUDED.updated_epoch, "
                + "sha256 = EXCLUDED.sha256";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, fileName);
            ps.setString(3, payload);
            ps.setLong(4, updatedEpoch);
            ps.setString(5, sha);
            ps.executeUpdate();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("No parent directory for target file: " + target);
        }

        Path tmp = Files.createTempFile(dir, ".robin-config-sync-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);

            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName cannot be null or empty");
        }
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("tableName contains invalid characters: " + tableName);
        }
    }
}

