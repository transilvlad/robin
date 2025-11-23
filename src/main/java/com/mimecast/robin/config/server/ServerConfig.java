package com.mimecast.robin.config.server;

import com.google.gson.Gson;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.ConfigFoundation;
import com.mimecast.robin.smtp.session.Session;
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
        CONFIG_FILENAMES.put("clamav", "clamav.json5");
        CONFIG_FILENAMES.put("rspamd", "rspamd.json5");
        CONFIG_FILENAMES.put("blocklist", "blocklist.json5");
        CONFIG_FILENAMES.put("blackhole", "blackhole.json5");
        CONFIG_FILENAMES.put("proxy", "proxy.json5");
        CONFIG_FILENAMES.put("bots", "bots.json5");
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
        return new ListenerConfig(getMapProperty("smtpConfig"));
    }

    /**
     * Gets secure port listener configuration.
     *
     * @return ListenerConfig instance.
     */
    public ListenerConfig getSecureConfig() {
        return new ListenerConfig(getMapProperty("secureConfig"));
    }

    /**
     * Gets submission port listener configuration.
     *
     * @return ListenerConfig instance.
     */
    public ListenerConfig getSubmissionConfig() {
        return new ListenerConfig(getMapProperty("submissionConfig"));
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
     * Allows accepting self-signed certificates when true.
     * ONLY enable for local testing; never in production.
     *
     * @return Boolean (default: false).
     */
    public boolean isAllowSelfSigned() {
        return getBooleanProperty("allowSelfSigned", false);
    }

    /**
     * Gets service endpoint configuration.
     *
     * @return EndpointConfig instance for service.
     */
    public EndpointConfig getService() {
        return getEndpointConfig("service");
    }

    /**
     * Gets API endpoint configuration.
     *
     * @return EndpointConfig instance for API.
     */
    public EndpointConfig getApi() {
        return getEndpointConfig("api");
    }

    /**
     * Gets endpoint configuration with magic replacement applied.
     *
     * @param key         Configuration key (service or api).
     * @return EndpointConfig instance.
     */
    private EndpointConfig getEndpointConfig(String key) {
        if (map.containsKey(key) && map.get(key) instanceof Map) {
            Map<String, Object> endpointMap = getMapProperty(key);
            Map<String, Object> processedMap = applyMagicToConfig(endpointMap);
            return new EndpointConfig(processedMap);
        }
        return new EndpointConfig(new HashMap<>());
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
     * Gets blocklist configuration.
     *
     * @return BlocklistConfig instance.
     */
    public BlocklistConfig getBlocklistConfig() {
        loadExternalIfAbsent("blocklist", Map.class);

        if (map.containsKey("blocklist")) {
            return new BlocklistConfig(getMapProperty("blocklist"));
        }
        // Return default config if not defined.
        return new BlocklistConfig(null);
    }

    /**
     * Gets blackhole configuration.
     *
     * @return BlackholeConfig instance.
     */
    public BlackholeConfig getBlackholeConfig() {
        loadExternalIfAbsent("blackhole", Map.class);

        if (map.containsKey("blackhole")) {
            return new BlackholeConfig(getMapProperty("blackhole"));
        }
        // Return default config if not defined.
        return new BlackholeConfig(null);
    }

    /**
     * Gets proxy configuration.
     *
     * @return ProxyConfig instance.
     */
    public ProxyConfig getProxy() {
        loadExternalIfAbsent("proxy", Map.class);

        if (map.containsKey("proxy")) {
            return new ProxyConfig(getMapProperty("proxy"));
        }
        // Return default config if not defined.
        return new ProxyConfig(null);
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
     * Gets ClamAV config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getClamAV() {
        loadExternalIfAbsent("clamav", Map.class);
        return new BasicConfig(getMapProperty("clamav"));
    }

    /**
     * Gets Rspamd config.
     *
     * @return RspamdConfig instance.
     */
    public RspamdConfig getRspamd() {
        loadExternalIfAbsent("rspamd", Map.class);
        
        if (map.containsKey("rspamd")) {
            return new RspamdConfig(getMapProperty("rspamd"));
        }
        return new RspamdConfig(new HashMap<>());
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
     * Is chaos headers enabled.
     * <p>WARNING: This feature is intended for testing purposes only.
     * Do NOT enable in production environments as it allows bypassing normal processing.
     *
     * @return Boolean (default: false).
     */
    public boolean isChaosHeaders() {
        return getBooleanProperty("chaosHeaders", false);
    }

    /**
     * Is XCLIENT extension enabled.
     * <p>WARNING: This feature is intended for development and testing purposes only.
     * Do NOT enable in production environments as XCLIENT allows clients to forge sender information.
     *
     * @return Boolean (default: false).
     */
    public boolean isXclientEnabled() {
        return getBooleanProperty("xclientEnabled", false);
    }

    /**
     * Applies magic replacement to string values in a configuration map.
     *
     * @param config Configuration map to process.
     * @return New map with magic replacements applied.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyMagicToConfig(Map<String, Object> config) {
        Map<String, Object> processed = new HashMap<>();
        Session session = new Session();
        
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                processed.put(entry.getKey(), Magic.magicReplace((String) value, session));
            } else if (value instanceof List) {
                List<Object> list = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    if (item instanceof String) {
                        list.add(Magic.magicReplace((String) item, session));
                    } else {
                        list.add(item);
                    }
                }
                processed.put(entry.getKey(), list);
            } else {
                processed.put(entry.getKey(), value);
            }
        }
        return processed;
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
     * Gets bot configuration.
     *
     * @return BotConfig instance.
     */
    public BotConfig getBots() {
        loadExternalIfAbsent("bots", Map.class);

        if (map.containsKey("bots")) {
            return new BotConfig(getMapProperty("bots"));
        }
        // Return default config if not defined.
        return new BotConfig(null);
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
