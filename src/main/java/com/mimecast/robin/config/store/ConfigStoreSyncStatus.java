package com.mimecast.robin.config.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status of the last configuration store synchronization attempt.
 *
 * <p>This is designed for safe operational visibility and is returned via
 * {@code GET /config/sync/status}.
 *
 * <p>The status intentionally contains only filenames and summary counters.
 * It does not expose configuration contents or secrets.
 */
public final class ConfigStoreSyncStatus {
    private final boolean enabled;
    private final long lastAttemptEpochMillis;
    private final long lastSuccessEpochMillis;
    private final String lastError;
    private final List<String> appliedFiles;
    private final List<String> skippedFiles;

    ConfigStoreSyncStatus(boolean enabled,
                          long lastAttemptEpochMillis,
                          long lastSuccessEpochMillis,
                          String lastError,
                          List<String> appliedFiles,
                          List<String> skippedFiles) {
        this.enabled = enabled;
        this.lastAttemptEpochMillis = lastAttemptEpochMillis;
        this.lastSuccessEpochMillis = lastSuccessEpochMillis;
        this.lastError = lastError;
        this.appliedFiles = appliedFiles != null ? List.copyOf(appliedFiles) : List.of();
        this.skippedFiles = skippedFiles != null ? List.copyOf(skippedFiles) : List.of();
    }

    public static ConfigStoreSyncStatus disabled() {
        return new ConfigStoreSyncStatus(false, 0L, 0L, null, List.of(), List.of());
    }

    /**
     * Converts the status to a simple map suitable for JSON serialization.
     *
     * @return A JSON-serializable map.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("lastAttemptEpochMillis", lastAttemptEpochMillis);
        map.put("lastSuccessEpochMillis", lastSuccessEpochMillis);
        if (lastError != null) {
            map.put("lastError", lastError);
        }
        map.put("appliedFiles", new ArrayList<>(appliedFiles));
        map.put("skippedFiles", new ArrayList<>(skippedFiles));
        map.put("appliedCount", appliedFiles.size());
        map.put("skippedCount", skippedFiles.size());
        return map;
    }
}

