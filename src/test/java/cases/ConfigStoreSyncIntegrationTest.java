package cases;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mimecast.robin.config.store.ConfigStoreSyncManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the PostgreSQL-backed configuration store.
 *
 * <p>The isolated integration PostgreSQL URL must be explicitly supplied using
 * {@code robin.integration.jdbcUrl}; there is no production endpoint default.
 */
@Tag("integration")
@Tag("suite")
@Isolated // ConfigStoreSyncManager.configDir is static and reset by Foundation.init in other test classes
public class ConfigStoreSyncIntegrationTest {

    @Test
    void testSync_keepsSecretReferencesOutOfStoredPayloads() throws Exception {
        String jdbcUrl = System.getProperty("robin.integration.jdbcUrl");
        assertNotNull(jdbcUrl, "Set robin.integration.jdbcUrl to the isolated integration PostgreSQL URL");
        Path dir = Files.createTempDirectory("robin-secret-store-test-");
        String table = "config_store_test_" + UUID.randomUUID().toString().replace("-", "");
        Path secrets = dir.resolve("secrets.properties");
        Files.writeString(secrets, "testSecret=only-in-memory\n");
        Files.writeString(dir.resolve("config.json5"), """
                {enabled: true, jdbcUrl: "%s", username: "robin", password: "robin", tableName: "%s"}
                """.formatted(jdbcUrl, table));
        Files.writeString(dir.resolve("server.json5"), "{smtpPort: 25, authValue: '{$secret:testSecret}'}");
        String previous = System.getProperty("secrets.file");
        System.setProperty("secrets.file", secrets.toString());
        try {
            ConfigStoreSyncManager.setConfigDir(dir.toString());
            ConfigStoreSyncManager.syncIfEnabled();
            try (Connection c = DriverManager.getConnection(jdbcUrl, "robin", "robin");
                 Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT payload_json::text FROM " + table + " WHERE file_name='server.json5'")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("{$secret:testSecret}"));
                assertFalse(rs.getString(1).contains("only-in-memory"));
            }
            assertEquals("only-in-memory", new com.mimecast.robin.config.BasicConfig(
                    dir.resolve("server.json5").toString()).getStringProperty("authValue"));
            assertFalse(Files.readString(dir.resolve("server.json5")).contains("only-in-memory"));
        } finally {
            if (previous == null) {
                System.clearProperty("secrets.file");
            } else {
                System.setProperty("secrets.file", previous);
            }
            try (Connection c = DriverManager.getConnection(jdbcUrl, "robin", "robin");
                 Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            Files.deleteIfExists(dir.resolve("server.json5"));
            Files.deleteIfExists(dir.resolve("config.json5"));
            Files.deleteIfExists(secrets);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void testSync_rejectsEmptyCriticalPayload() throws Exception {
        String jdbcUrl = System.getProperty("robin.integration.jdbcUrl");
        assertNotNull(jdbcUrl, "Set robin.integration.jdbcUrl to the isolated integration PostgreSQL URL");
        Path dir = Files.createTempDirectory("robin-config-store-test-");
        String table = "config_store_test_" + UUID.randomUUID().toString().replace("-", "");

        Files.writeString(dir.resolve("config.json5"), """
                {
                  enabled: true,
                  serverId: "robin1",
                  jdbcUrl: "%s",
                  username: "robin",
                  password: "robin",
                  tableName: "%s"
                }
                """.formatted(jdbcUrl, table), StandardCharsets.UTF_8);

        Files.writeString(dir.resolve("server.json5"), "{\"smtpPort\":25,\"securePort\":465}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("properties.json5"), "{\"intervalSeconds\":300}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("client.json5"), "{\"mail\":{\"from\":\"test@example.com\"}}", StandardCharsets.UTF_8);

        ConfigStoreSyncManager.setConfigDir(dir.toString());
        ConfigStoreSyncManager.syncIfEnabled();
        assertTrue(ConfigStoreSyncManager.getStatus().toMap().containsKey("lastSuccessEpochMillis"));

        try (Connection c = DriverManager.getConnection(jdbcUrl, "robin", "robin")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE " + table + " SET payload_json='{}'::jsonb WHERE server_id='default' AND file_name='server.json5'");
            }
        }

        ConfigStoreSyncManager.syncIfEnabled();

        JsonElement serverAfterRejected = parseJsonLenient(Files.readString(dir.resolve("server.json5"), StandardCharsets.UTF_8));
        assertNotNull(serverAfterRejected);
        assertTrue(serverAfterRejected.isJsonObject());
        assertFalse(serverAfterRejected.getAsJsonObject().isEmpty());

        try (Connection c = DriverManager.getConnection(jdbcUrl, "robin", "robin")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE " + table + " SET payload_json='{\"smtpPort\":25,\"securePort\":465}'::jsonb WHERE server_id='default' AND file_name='server.json5'");

                st.executeUpdate("INSERT INTO " + table + " (server_id, file_name, payload_json, updated_epoch, sha256) "
                        + "VALUES ('robin1', 'server.json5', '{\"smtpPort\":2525,\"newKey\":123}'::jsonb, extract(epoch from now())::bigint, repeat('0', 64)) "
                        + "ON CONFLICT (server_id, file_name) DO UPDATE SET "
                        + "payload_json = EXCLUDED.payload_json, updated_epoch = EXCLUDED.updated_epoch, sha256 = EXCLUDED.sha256");
            }
        }

        ConfigStoreSyncManager.syncIfEnabled();

        JsonElement serverAfterApplied = parseJsonLenient(Files.readString(dir.resolve("server.json5"), StandardCharsets.UTF_8));
        assertNotNull(serverAfterApplied);
        assertTrue(serverAfterApplied.isJsonObject());
        assertNotNull(serverAfterApplied.getAsJsonObject().get("smtpPort"));
        assertEquals(2525, serverAfterApplied.getAsJsonObject().get("smtpPort").getAsInt());
        assertEquals(465, serverAfterApplied.getAsJsonObject().get("securePort").getAsInt());
        assertFalse(serverAfterApplied.getAsJsonObject().has("newKey"));

        try (Connection c = DriverManager.getConnection(jdbcUrl, "robin", "robin")) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static JsonElement parseJsonLenient(String content) {
        try {
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true);
            return JsonParser.parseReader(reader);
        } catch (Exception e) {
            return null;
        }
    }
}
