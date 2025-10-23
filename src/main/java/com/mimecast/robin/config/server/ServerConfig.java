package com.mimecast.robin.config.server;

import com.google.gson.Gson;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.ConfigFoundation;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Server configuration.
 *
 * <p>This class provides type safe access to server configuration.
 * <p>It also maps authentication users and behaviour scenarios to corresponding objects.
 *
 * @see UserConfig
 * @see ScenarioConfig
 */
@SuppressWarnings("unchecked")
public class ServerConfig extends ConfigFoundation {

    /**
     * Configuration directory.
     */
    private String configDir;

    /**
     * Mapping of configuration keys to their filenames for lazy loading.
     */
    private static final Map<String, String> CONFIG_FILENAMES = new HashMap<>();

    static {
        CONFIG_FILENAMES.put("webhooks", "webhooks.json5");
        CONFIG_FILENAMES.put("storage", "storage.json5");
        CONFIG_FILENAMES.put("queue", "queue.json5");
        CONFIG_FILENAMES.put("relay", "relay.json5");
        CONFIG_FILENAMES.put("dovecot", "dovecot.json5");
        CONFIG_FILENAMES.put("prometheus", "prometheus.json5");
        CONFIG_FILENAMES.put("users", "users.json5");
        CONFIG_FILENAMES.put("scenarios", "scenarios.json5");
        CONFIG_FILENAMES.put("vault", "vault.json5");
    }

    /**
     * Constructs a new ServerConfig instance.
     */
    public ServerConfig() {
        super();
        this.configDir = null;
    }

    /**
     * Constructs a new ServerConfig instance.
     *
     * @param map Configuration map.
     */
    public ServerConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Constructs a new ServerConfig instance with configuration path.
     *
     * @param path Path to configuration file.
     * @throws IOException Unable to read file.
     */
    public ServerConfig(String path) throws IOException {
        super(path);
        this.configDir = new File(path).getParent();
    }

    /**
     * Gets hostname.
     *
     * @return Hostname.
     */
    public String getHostname() {
        return getStringProperty("hostname", "example.com");
    }

    /**
     * Gets bind address.
     *
     * @return Bind address string.
     */
    public String getBind() {
        return getStringProperty("bind", "::");
    }

    /**
     * Gets SMTP port.
     *
     * @return Bind address number.
     */
    public int getSmtpPort() {
        return Math.toIntExact(getLongProperty("smtpPort", 25L));
    }

    /**
     * Gets SMTPS port.
     *
     * @return Bind address number.
     */
    public int getSecurePort() {
        return Math.toIntExact(getLongProperty("securePort", 465L));
    }

    /**
     * Gets Submission port.
     *
     * @return Bind address number.
     */
    public int getSubmissionPort() {
        return Math.toIntExact(getLongProperty("submissionPort", 587L));
    }

    /**
     * Gets SMTP port listener configuration.
     *
     * @return ListenerConfig instance.
     */
    public ListenerConfig getSmtpConfig() {
        if (map.containsKey("smtpConfig")) {
            return new ListenerConfig(getMapProperty("smtpConfig"));
        }
        // Fallback to legacy flat config
        return new ListenerConfig(map);
    }

    /**
     * Gets secure port listener configuration.
     *
     * @return ListenerConfig instance.
     */
    public ListenerConfig getSecureConfig() {
        if (map.containsKey("secureConfig")) {
            return new ListenerConfig(getMapProperty("secureConfig"));
        }
        // Fallback to legacy flat config
        return new ListenerConfig(map);
    }

    /**
     * Gets submission port listener configuration.
     *
     * @return ListenerConfig instance.
     */
    public ListenerConfig getSubmissionConfig() {
        if (map.containsKey("submissionConfig")) {
            return new ListenerConfig(getMapProperty("submissionConfig"));
        }
        // Fallback to legacy flat config
        return new ListenerConfig(map);
    }

    /**
     * Gets backlog size.
     * @deprecated Use getSmtpConfig().getBacklog() instead.
     *
     * @return Backlog size.
     */
    @Deprecated
    public int getBacklog() {
        return getSmtpConfig().getBacklog();
    }

    /**
     * Gets minimum pool size.
     * @deprecated Use getSmtpConfig().getMinimumPoolSize() instead.
     *
     * @return Thread pool min size.
     */
    @Deprecated
    public int getMinimumPoolSize() {
        return getSmtpConfig().getMinimumPoolSize();
    }

    /**
     * Gets maximum pool size.
     * @deprecated Use getSmtpConfig().getMaximumPoolSize() instead.
     *
     * @return Thread pool max size.
     */
    @Deprecated
    public int getMaximumPoolSize() {
        return getSmtpConfig().getMaximumPoolSize();
    }

    /**
     * Gets thread keep alive time.
     * @deprecated Use getSmtpConfig().getThreadKeepAliveTime() instead.
     *
     * @return Time in seconds.
     */
    @Deprecated
    public int getThreadKeepAliveTime() {
        return getSmtpConfig().getThreadKeepAliveTime();
    }

    /**
     * Gets transactions limit.
     * <p>This defines how many commands will be processed before breaking receipt loop.
     * @deprecated Use getSmtpConfig().getTransactionsLimit() instead.
     *
     * @return Error limit.
     */
    @Deprecated
    public int getTransactionsLimit() {
        return getSmtpConfig().getTransactionsLimit();
    }

    /**
     * Gets error limit.
     * <p>This defines how many syntax errors should be permitted before iterrupting the receipt.
     * @deprecated Use getSmtpConfig().getErrorLimit() instead.
     *
     * @return Error limit.
     */
    @Deprecated
    public int getErrorLimit() {
        return getSmtpConfig().getErrorLimit();
    }

    /**
     * Is AUTH enabled.
     *
     * @return Boolean.
     */
    public boolean isAuth() {
        return getBooleanProperty("auth", false);
    }

    /**
     * Is STARTTLS enabled.
     *
     * @return Boolean.
     */
    public boolean isStartTls() {
        return getBooleanProperty("starttls", true);
    }

    /**
     * Is CHUNKING enabled.
     *
     * @return Boolean.
     */
    public boolean isChunking() {
        return getBooleanProperty("chunking", true);
    }

    /**
     * Gets key store.
     *
     * @return Key store path.
     */
    public String getKeyStore() {
        return getStringProperty("keystore", "/usr/local/keystore.jks");
    }

    /**
     * Gets key store password.
     *
     * @return Key store password string or path.
     */
    public String getKeyStorePassword() {
        return getStringProperty("keystorepassword", "");
    }

    /**
     * Gets trust store.
     *
     * @return Trust store path.
     */
    public String getTrustStore() {
        return getStringProperty("truststore", "/usr/local/truststore.jks");
    }

    /**
     * Gets trust store password.
     *
     * @return Trust store password string or path.
     */
    public String getTrustStorePassword() {
        return getStringProperty("truststorepassword", "");
    }

    /**
     * Gets metrics port.
     *
     * @return Bind address number.
     */
    public int getMetricsPort() {
        return Math.toIntExact(getLongProperty("metricsPort", 8080L));
    }

    /**
     * Gets metrics authentication username.
     *
     * @return Username for metrics endpoint authentication, or null if not configured.
     */
    public String getMetricsUsername() {
        return getStringProperty("metricsUsername", null);
    }

    /**
     * Gets metrics authentication password.
     *
     * @return Password for metrics endpoint authentication, or null if not configured.
     */
    public String getMetricsPassword() {
        return getStringProperty("metricsPassword", null);
    }

    /**
     * Gets API port for client submission endpoint.
     *
     * @return Port number.
     */
    public int getApiPort() {
        return Math.toIntExact(getLongProperty("apiPort", 8090L));
    }

    /**
     * Gets API authentication username.
     *
     * @return Username for API endpoint authentication, or null if not configured.
     */
    public String getApiUsername() {
        return getStringProperty("apiUsername", null);
    }

    /**
     * Gets API authentication password.
     *
     * @return Password for API endpoint authentication, or null if not configured.
     */
    public String getApiPassword() {
        return getStringProperty("apiPassword", null);
    }

    /**
     * Gets Vault configuration.
     *
     * @return VaultConfig instance.
     */
    public VaultConfig getVault() {
        loadExternalIfAbsent("vault", Map.class);

        if (map.containsKey("vault")) {
            return new VaultConfig(getMapProperty("vault"));
        }
        return new VaultConfig(new HashMap<>());
    }

    /**
     * Gets RBL (Realtime Blackhole List) configuration.
     *
     * @return RblConfig instance.
     */
    public RblConfig getRblConfig() {
        if (map.containsKey("rbl")) {
            return new RblConfig(getMapProperty("rbl"));
        }
        // Return default config if not defined.
        return new RblConfig(null);
    }

    /**
     * Gets webhooks map.
     *
     * @return Webhooks map indexed by extension name.
     */
    @SuppressWarnings("rawtypes")
    public Map<String, WebhookConfig> getWebhooks() {
        loadExternalIfAbsent("webhooks", Map.class);

        Map<String, WebhookConfig> webhooks = new HashMap<>();
        if (map.containsKey("webhooks")) {
            for (Object object : getMapProperty("webhooks").entrySet()) {
                Map.Entry entry = (Map.Entry) object;
                webhooks.put((String) entry.getKey(), new WebhookConfig((Map) entry.getValue()));
            }
        }
        return webhooks;
    }

    /**
     * Gets storage config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getStorage() {
        loadExternalIfAbsent("storage", Map.class);
        return new BasicConfig(getMapProperty("storage"));
    }

    /**
     * Gets queue config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getQueue() {
        loadExternalIfAbsent("queue", Map.class);
        return new BasicConfig(getMapProperty("queue"));
    }

    /**
     * Gets relay config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getRelay() {
        loadExternalIfAbsent("relay", Map.class);
        return new BasicConfig(getMapProperty("relay"));
    }

    /**
     * Gets dovecot config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getDovecot() {
        loadExternalIfAbsent("dovecot", Map.class);
        return new BasicConfig(getMapProperty("dovecot"));
    }

    /**
     * Gets Prometheus remote write config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getPrometheus() {
        loadExternalIfAbsent("prometheus", Map.class);
        return new BasicConfig(getMapProperty("prometheus"));
    }

    /**
     * Is users enabled.
     *
     * @return Boolean.
     */
    public boolean isUsersEnabled() {
        return !getDovecot().getBooleanProperty("auth") &&
                getBooleanProperty("usersEnabled", false);
    }

    /**
     * Gets users list.
     *
     * @return Users list.
     */
    public List<UserConfig> getUsers() {
        loadExternalIfAbsent("users", List.class);

        List<UserConfig> users = new ArrayList<>();
        for (Map<String, String> user : (List<Map<String, String>>) getListProperty("users")) {
            users.add(new UserConfig(user));
        }
        return users;
    }

    /**
     * Gets user by username.
     *
     * @param find Username to find.
     * @return Optional of UserConfig.
     */
    public Optional<UserConfig> getUser(String find) {
        for (UserConfig user : getUsers()) {
            if (user.getName().equals(find)) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets scenarios map.
     *
     * @return Scenarios map.
     */
    @SuppressWarnings("rawtypes")
    public Map<String, ScenarioConfig> getScenarios() {
        loadExternalIfAbsent("scenarios", Map.class);

        Map<String, ScenarioConfig> scenarios = new HashMap<>();
        if (map.containsKey("scenarios")) {
            for (Object object : getMapProperty("scenarios").entrySet()) {
                Map.Entry entry = (Map.Entry) object;
                scenarios.put((String) entry.getKey(), new ScenarioConfig((Map) entry.getValue()));
            }
        }
        return scenarios;
    }

    /**
     * Helper to lazily load an external JSON5 file into the root config map under the given key
     * if the key is absent and a config directory is available.
     *
     * @param key   Root key to populate in the map.
     * @param clazz Class to parse the JSON into (e.g., Map.class, List.class).
     */
    private void loadExternalIfAbsent(String key, Class<?> clazz) {
        if (!map.containsKey(key) && configDir != null && CONFIG_FILENAMES.containsKey(key)) {
            String filename = CONFIG_FILENAMES.get(key);
            String path = configDir + File.separator + filename;
            if (PathUtils.isFile(path)) {
                try {
                    String content = Magic.streamMagicReplace(PathUtils.readFile(path, Charset.defaultCharset()));
                    Object parsed = new Gson().fromJson(content, clazz);
                    map.put(key, parsed);
                } catch (IOException e) {
                    log.error("Failed to load " + key + " from " + path, e);
                }
            }
        }
    }
}
