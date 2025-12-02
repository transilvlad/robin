package com.mimecast.robin.config;

import java.util.Map;

/**
 * Typed Dovecot-specific configuration extending BasicConfig.
 * Provides convenience getters for commonly used dovecot config keys.
 */
public class DovecotConfig extends BasicConfig {

    public static class AuthSocket {
        private final String client;
        private final String userdb;

        public AuthSocket(Map<String, Object> map) {
            this.client = map.getOrDefault("client", "").toString();
            this.userdb = map.getOrDefault("userdb", "").toString();
        }

        public String getClient() { return client; }
        public String getUserdb() { return userdb; }
    }

    public DovecotConfig(String path) throws java.io.IOException {
        super(path);
    }

    public DovecotConfig(Map<String, Object> map) {
        super(map);
    }

    public boolean isAuth() {
        return getBooleanProperty("auth", false);
    }

    public String getAuthBackend() {
        return getStringProperty("authBackend", "dovecot");
    }

    public AuthSocket getAuthSocket() {
        if (map.containsKey("authSocket") && map.get("authSocket") instanceof Map) {
            return new AuthSocket(getMapProperty("authSocket"));
        }
        // Fallback to legacy keys (not needed per request but useful for migration)
        return new AuthSocket(Map.of(
                "client", getStringProperty("authClientSocket", ""),
                "userdb", getStringProperty("authUserdbSocket", "")
        ));
    }

    public String getAuthSqlJdbcUrl() { return getStringProperty("authSql.jdbcUrl", ""); }
    public String getAuthSqlUser() { return getStringProperty("authSql.user", ""); }
    public String getAuthSqlPassword() { return getStringProperty("authSql.password", ""); }
    public String getAuthSqlPasswordQuery() { return getStringProperty("authSql.passwordQuery", ""); }
    public String getAuthSqlUserQuery() { return getStringProperty("authSql.userQuery", ""); }

    public boolean isSaveToDovecotLda() { return getBooleanProperty("saveToDovecotLda", false); }
    public long getInlineSaveMaxAttempts() { return getLongProperty("inlineSaveMaxAttempts", 2L); }
    public long getInlineSaveRetryDelay() { return getLongProperty("inlineSaveRetryDelay", 3L); }
    public String getLdaBinary() { return getStringProperty("ldaBinary", "/usr/libexec/dovecot/dovecot-lda"); }
    public String getInboxFolder() { return getStringProperty("inboxFolder", "INBOX"); }
    public String getSentFolder() { return getStringProperty("sentFolder", "Sent"); }
    public String getFailureBehaviour() { return getStringProperty("failureBehaviour", "retry"); }
    public int getMaxRetryCount() { return Math.toIntExact(getLongProperty("maxRetryCount", 10L)); }
}

