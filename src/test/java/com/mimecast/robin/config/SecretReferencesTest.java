package com.mimecast.robin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SecretReferencesTest {
    @TempDir
    Path dir;

    @Test
    void resolvesNestedReferencesWithoutJsonInjection() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "password=quote\\\"\\\\backslash\\u00e9\n");
        withSecretsFile(file, () -> {
            String resolved = SecretReferences.resolve("{nested: {password: '{$secret:password}'}, values: ['{$secret:password}']}");
            var json = new com.google.gson.Gson().fromJson(resolved, com.google.gson.JsonObject.class);
            assertEquals("quote\"\\backslash\u00e9", json.getAsJsonObject("nested").get("password").getAsString());
            assertEquals("quote\"\\backslash\u00e9", json.getAsJsonArray("values").get(0).getAsString());
        });
    }

    @Test
    void missingEmptyUnreadableAndEmbeddedReferencesFail() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "empty=\n");
        withSecretsFile(file, () -> {
            assertThrows(IllegalStateException.class, () -> SecretReferences.resolve("{password: '{$secret:ROBIN_TEST_MISSING_SECRET}'}"));
            assertThrows(IllegalStateException.class, () -> SecretReferences.resolve("{password: '{$secret:empty}'}"));
            assertThrows(IllegalStateException.class, () -> SecretReferences.resolve("{password: 'prefix{$secret:empty}'}"));
        });
        withSecretsFile(dir.resolve("missing"), () ->
                assertThrows(IllegalStateException.class, () -> SecretReferences.resolve("{password: '{$secret:password}'}")));
    }

    @Test
    void preservesOrdinaryMagicAndConfiguration() {
        String content = "{value: '{$transactionUid}', enabled: true}";
        assertEquals(content, SecretReferences.resolve(content));
    }

    @Test
    void fileConfigAndExternalServerConfigResolveSecrets() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "password=external-value\n");
        Files.writeString(dir.resolve("server.json5"), "{smtpPort: 25}");
        Files.writeString(dir.resolve("dovecot.json5"), "{authSql: {enabled: true, password: '{$secret:password}'}}");
        withSecretsFile(file, () -> {
            try {
                var server = new com.mimecast.robin.config.server.ServerConfig(dir.resolve("server.json5").toString());
                assertEquals("external-value", new BasicConfig(server.getDovecot().getMapProperty("authSql")).getStringProperty("password"));
                assertTrue(Files.readString(dir.resolve("dovecot.json5")).contains("{$secret:password}"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Test
    void fileEnvironmentTakesPrecedenceAndPreservesSecretCharacters() throws Exception {
        Path file = dir.resolve("password");
        Files.writeString(file, "quote\"\\backslash \r\n");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        builder.environment().put("ROBIN_TEST_SECRET_FILE", file.toString());
        builder.environment().put("ROBIN_TEST_SECRET", "not-selected");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        var json = new com.google.gson.Gson().fromJson(output, com.google.gson.JsonObject.class);
        assertEquals("quote\"\\backslash ", json.get("password").getAsString());
    }

    public static class Probe {
        public static void main(String[] args) {
            System.out.print(SecretReferences.resolve("{password: '{$secret:ROBIN_TEST_SECRET}'}"));
        }
    }

    private void withSecretsFile(Path path, Runnable test) {
        String previous = System.getProperty("secrets.file");
        System.setProperty("secrets.file", path.toString());
        try {
            test.run();
        } finally {
            if (previous == null) {
                System.clearProperty("secrets.file");
            } else {
                System.setProperty("secrets.file", previous);
            }
        }
    }
}
