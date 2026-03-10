package com.mimecast.robin.config;

import java.util.Map;

/**
 * Typed Stalwart direct-ingest configuration.
 */
public class StalwartConfig extends BasicConfig {

    public StalwartConfig(Map<String, Object> map) {
        super(map);
    }

    public boolean isEnabled() {
        return getBooleanProperty("enabled", false);
    }

    public boolean isInline() {
        return getBooleanProperty("inline", true);
    }

    public String getBaseUrl() {
        return getStringProperty("baseUrl", "http://127.0.0.1:8080");
    }

    public String getManagementBaseUrl() {
        String managementBaseUrl = getStringProperty("managementBaseUrl", "");
        return managementBaseUrl == null || managementBaseUrl.isBlank() ? getBaseUrl() : managementBaseUrl;
    }

    public String getUsername() {
        return getStringProperty("username", "admin");
    }

    public String getPassword() {
        return getStringProperty("password", "");
    }

    public long getConnectTimeoutSeconds() {
        return getLongProperty("connectTimeoutSeconds", 10L);
    }

    public long getReadTimeoutSeconds() {
        return getLongProperty("readTimeoutSeconds", 30L);
    }

    public long getWriteTimeoutSeconds() {
        return getLongProperty("writeTimeoutSeconds", 30L);
    }

    public int getLookupCacheTtlSeconds() {
        return Math.toIntExact(getLongProperty("lookupCacheTtlSeconds", 300L));
    }

    public int getLookupCacheMaxEntries() {
        return Math.toIntExact(getLongProperty("lookupCacheMaxEntries", 4096L));
    }

    public int getMaxConcurrentRequests() {
        return Math.toIntExact(getLongProperty("maxConcurrentRequests", 32L));
    }

    public String getFailureBehaviour() {
        return getStringProperty("failureBehaviour", "retry");
    }

    public int getMaxRetryCount() {
        return Math.toIntExact(getLongProperty("maxRetryCount", 10L));
    }

    public String getInboxMailboxId() {
        return getStringProperty("inboxMailboxId", "0");
    }
}
