package com.mimecast.robin.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves explicit secret references in configuration values, never in stored payloads.
 */
public final class SecretReferences {
    private static final Pattern REFERENCE = Pattern.compile("^\\{\\$secret:([A-Za-z0-9_.-]+)}$");
    private static final Gson GSON = new Gson();

    private SecretReferences() {
        throw new IllegalStateException("Static class");
    }

    /**
     * Resolves whole-string {@code {$secret:NAME}} values from {@code secrets.file}
     * or {@code NAME_FILE}/{@code NAME} environment variables.
     * File values take precedence. Missing secrets are fatal.
     *
     * @param content Configuration JSON5 after ordinary magic processing.
     * @return JSON with resolved in-memory values.
     */
    public static String resolve(String content) {
        if (!content.contains("{$secret:")) {
            return content;
        }
        Properties secrets = new Properties();
        String file = System.getProperty("secrets.file");
        if (file != null) {
            try (Reader reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
                secrets.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read configured secrets file");
            }
        }
        JsonElement json = GSON.fromJson(content, JsonElement.class);
        return GSON.toJson(resolve(json, secrets));
    }

    private static JsonElement resolve(JsonElement json, Properties secrets) {
        if (json == null || json.isJsonNull()) {
            return json;
        }
        if (json.isJsonObject()) {
            for (var entry : json.getAsJsonObject().entrySet()) {
                entry.setValue(resolve(entry.getValue(), secrets));
            }
        } else if (json.isJsonArray()) {
            for (int i = 0; i < json.getAsJsonArray().size(); i++) {
                json.getAsJsonArray().set(i, resolve(json.getAsJsonArray().get(i), secrets));
            }
        } else if (json.getAsJsonPrimitive().isString()) {
            String value = json.getAsString();
            Matcher matcher = REFERENCE.matcher(value);
            if (matcher.matches()) {
                String name = matcher.group(1);
                String secret = secrets.getProperty(name);
                if (secret == null) {
                    String secretFile = System.getenv(name + "_FILE");
                    if (secretFile != null) {
                        try {
                            secret = Files.readString(Path.of(secretFile), StandardCharsets.UTF_8)
                                    .replaceFirst("\\r?\\n$", "");
                        } catch (IOException e) {
                            throw new IllegalStateException("Cannot read required secret: " + name);
                        }
                    } else {
                        secret = System.getenv(name);
                    }
                }
                if (secret == null || secret.isEmpty()) {
                    throw new IllegalStateException("Missing required secret: " + name);
                }
                return new JsonPrimitive(secret);
            }
            if (value.contains("{$secret:")) {
                throw new IllegalStateException("Secret references must occupy an entire configuration value");
            }
        }
        return json;
    }
}
