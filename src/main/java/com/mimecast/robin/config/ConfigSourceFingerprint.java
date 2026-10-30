package com.mimecast.robin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints parsed source JSON before magic or secret-reference expansion.
 */
public final class ConfigSourceFingerprint {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ConfigSourceFingerprint() {
    }

    public static String sha256(String source) {
        String canonical = GSON.toJson(sorted(JsonParser.parseString(source)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted()
                    .forEach(key -> object.add(key, sorted(value.getAsJsonObject().get(key))));
            return object;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(element -> array.add(sorted(element)));
            return array;
        }
        return value;
    }
}
