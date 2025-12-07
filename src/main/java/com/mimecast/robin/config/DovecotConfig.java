package com.mimecast.robin.config;

import java.util.Map;
import java.util.List;

/**
 * Typed Dovecot-specific configuration extending BasicConfig.
 * <p>
 * Provides typed accessors for authentication backend (socket or SQL) and mailbox delivery backend.
 * The delivery backend is automatically selected based on which backend has `enabled: true`.
 * LMTP takes precedence if both are enabled. Shared delivery options and backend-specific
 * configuration are grouped logically under <code>saveLda</code> and <code>saveLmtp</code> objects.
 */
public class DovecotConfig extends BasicConfig {

    /**
     * Constructs DovecotConfig from a configuration map.
     *
     * @param map Configuration map containing Dovecot settings.
     */
    public DovecotConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Checks if authentication is enabled.
     *
     * @return True if authentication is enabled, false otherwise.
     */
    public boolean isAuth() {
        return getBooleanProperty("auth", false);
    }

    /**
     * Gets Dovecot authentication socket configuration.
     *
     * @return AuthSocket instance with client and userdb socket paths.
     */
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

    /**
     * Gets SQL authentication configuration properties.
     */
    public String getAuthSqlJdbcUrl() { return getStringProperty("authSql.jdbcUrl", ""); }

    /**
     * Gets SQL authentication username.
     *
     * @return SQL authentication username.
     */
    public String getAuthSqlUser() { return getStringProperty("authSql.user", ""); }

    /**
     * Gets SQL authentication password.
     *
     * @return SQL authentication password.
     */
    public String getAuthSqlPassword() { return getStringProperty("authSql.password", ""); }

    /**
     * Gets SQL query to retrieve password hash for a user.
     *
     * @return SQL query string.
     */
    public String getAuthSqlPasswordQuery() { return getStringProperty("authSql.passwordQuery", ""); }

    /**
     * Gets SQL query to check if a user exists.
     *
     * @return SQL query string.
     */
    public String getAuthSqlUserQuery() { return getStringProperty("authSql.userQuery", ""); }

    /**
     * Gets LMTP backend configuration.
     *
     * @return SaveLmtp instance with LMTP settings.
     */
    public SaveLmtp getSaveLmtp() {
        if (map.containsKey("saveLmtp") && map.get("saveLmtp") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> safeMap = (Map<String, Object>) map.get("saveLmtp");
            return new SaveLmtp(safeMap);
        }
        return new SaveLmtp(Map.of());
    }

    /**
     * Gets LDA backend configuration.
     *
     * @return SaveLda instance with LDA settings.
     */
    public SaveLda getSaveLda() {
        if (map.containsKey("saveLda") && map.get("saveLda") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> safeMap = (Map<String, Object>) map.get("saveLda");
            return new SaveLda(safeMap);
        }
        return new SaveLda(Map.of());
    }

    /**
     * Gets maximum number of inline save attempts before giving up.
     * <p>
     * Applies to both LDA and LMTP backends.
     *
     * @return Maximum number of attempts (default: 2).
     */
    public long getInlineSaveMaxAttempts() { return getLongProperty("inlineSaveMaxAttempts", 2L); }

    /**
     * Gets delay (in seconds) between inline save retry attempts.
     * <p>
     * Applies to both LDA and LMTP backends.
     *
     * @return Delay in seconds (default: 3).
     */
    public long getInlineSaveRetryDelay() { return getLongProperty("inlineSaveRetryDelay", 3L); }

    /**
     * Gets behaviour on mailbox delivery failure.
     * <p>
     * Applies to both LDA and LMTP backends.
     *
     * @return Failure behaviour: "retry" or "bounce" (default: "retry").
     */
    public String getFailureBehaviour() { return getStringProperty("failureBehaviour", "retry"); }

    /**
     * Gets maximum retry count for outbound relay.
     * <p>
     * Applies to both LDA and LMTP backends.
     *
     * @return Maximum retry count (default: 10).
     */
    public int getMaxRetryCount() { return Math.toIntExact(getLongProperty("maxRetryCount", 10L)); }

    /**
     * Checks if Dovecot socket authentication backend is enabled.
     *
     * @return True if authSocket.enabled is true, false otherwise.
     */
    public boolean isAuthSocketEnabled() {
        if (map.containsKey("authSocket") && map.get("authSocket") instanceof Map) {
            Object enabled = ((Map<?, ?>) map.get("authSocket")).get("enabled");
            return enabled instanceof Boolean && (Boolean) enabled;
        }
        return false;
    }

    /**
     * Checks if Dovecot SQL authentication backend is enabled.
     *
     * @return True if authSql.enabled is true, false otherwise.
     */
    public boolean isAuthSqlEnabled() {
        if (map.containsKey("authSql") && map.get("authSql") instanceof Map) {
            Object enabled = ((Map<?, ?>) map.get("authSql")).get("enabled");
            return enabled instanceof Boolean && (Boolean) enabled;
        }
        return false;
    }

    /**
     * Dovecot authentication socket configuration.
     * <p>
     * Holds paths to Dovecot authentication sockets for client (SASL mechanisms)
     * and userdb (user existence checks).
     */
    public static class AuthSocket {
        private final String client;
        private final String userdb;

        /**
         * Constructs AuthSocket from configuration map.
         *
         * @param map Configuration map containing client and userdb socket paths.
         */
        public AuthSocket(Map<String, Object> map) {
            this.client = map.getOrDefault("client", "").toString();
            this.userdb = map.getOrDefault("userdb", "").toString();
        }

        /**
         * Gets Dovecot authentication client socket path.
         *
         * @return Socket path for SASL mechanisms.
         */
        public String getClient() { return client; }

        /**
         * Gets Dovecot userdb lookup socket path.
         *
         * @return Socket path for user existence checks.
         */
        public String getUserdb() { return userdb; }
    }

    /**
     * LMTP backend configuration.
     * <p>
     * Holds configuration for LMTP-based mailbox delivery. LMTP is the default backend and recommended for
     * distributed and SQL-backed setups. It does not require Robin and Dovecot in the same container.
     */
    public static class SaveLmtp {
        private final boolean enabled;
        private final List<String> servers;
        private final int port;
        private final boolean tls;

        /**
         * Constructs SaveLmtp from configuration map.
         *
         * @param map Configuration map containing LMTP settings.
         */
        public SaveLmtp(Map<String, Object> map) {
            this.enabled = Boolean.TRUE.equals(map.getOrDefault("enabled", true));
            @SuppressWarnings("unchecked")
            List<String> safeServers = (List<String>) map.getOrDefault("servers", List.of("127.0.0.1"));
            this.servers = safeServers;
            this.port = ((Number) map.getOrDefault("port", 24)).intValue();
            this.tls = Boolean.TRUE.equals(map.getOrDefault("tls", false));
        }

        /**
         * Gets enablement status of LMTP backend.
         *
         * @return True if enabled, false otherwise.
         */
        public boolean isEnabled() { return enabled; }

        /**
         * Gets list of LMTP server addresses.
         *
         * @return List of server hostnames or IP addresses.
         */
        public List<String> getServers() { return servers; }

        /**
         * Gets LMTP server port.
         *
         * @return Port number (default: 24).
         */
        public int getPort() { return port; }

        /**
         * Gets TLS enablement for LMTP connections.
         *
         * @return True if TLS enabled, false otherwise.
         */
        public boolean isTls() { return tls; }
    }

    /**
     * LDA backend configuration.
     * <p>
     * Holds configuration for LDA-based mailbox delivery. LDA requires Robin and Dovecot in the same container
     * or host, and uses UNIX sockets and the LDA binary for delivery.
     */
    public static class SaveLda {

        private final boolean enabled;
        private final String ldaBinary;
        private final String inboxFolder;
        private final String sentFolder;

        /**
         * Constructs SaveLda from configuration map.
         *
         * @param map Configuration map containing LDA settings.
         */
        public SaveLda(Map<String, Object> map) {
            this.enabled = Boolean.TRUE.equals(map.getOrDefault("enabled", false));
            this.ldaBinary = map.getOrDefault("ldaBinary", "/usr/libexec/dovecot/dovecot-lda").toString();
            this.inboxFolder = map.getOrDefault("inboxFolder", "INBOX").toString();
            this.sentFolder = map.getOrDefault("sentFolder", "Sent").toString();
        }

        /**
         * Gets enablement status of LDA backend.
         *
         * @return True if enabled, false otherwise.
         */
        public boolean isEnabled() { return enabled; }

        /**
         * Gets path to Dovecot LDA binary.
         *
         * @return LDA binary path.
         */
        public String getLdaBinary() { return ldaBinary; }

        /**
         * Gets folder for inbound email delivery via LDA.
         *
         * @return Folder name (default: "INBOX").
         */
        public String getInboxFolder() { return inboxFolder; }

        /**
         * Gets folder for outbound email delivery via LDA.
         *
         * @return Folder name (default: "Sent").
         */
        public String getSentFolder() { return sentFolder; }
    }
}
