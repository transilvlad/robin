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
     * Constructs a new ServerConfig instance.
     */
    public ServerConfig() {
        super();
        this.configDir = null;
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
    public int getPort() {
        return Math.toIntExact(getLongProperty("port", 25L));
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
     * Gets backlog size.
     *
     * @return Backlog size.
     */
    public int getBacklog() {
        return Math.toIntExact(getLongProperty("backlog", 25L));
    }

    /**
     * Gets minimum pool size.
     *
     * @return Thread pool min size.
     */
    public int getMinimumPoolSize() {
        return Math.toIntExact(getLongProperty("minimumPoolSize", 1L));
    }

    /**
     * Gets maximum pool size.
     *
     * @return Thread pool max size.
     */
    public int getMaximumPoolSize() {
        return Math.toIntExact(getLongProperty("maximumPoolSize", 10L));
    }

    /**
     * Gets thread keep alive time.
     *
     * @return Time in seconds.
     */
    public int getThreadKeepAliveTime() {
        return Math.toIntExact(getLongProperty("threadKeepAliveTime", 60L));
    }

    /**
     * Gets transactions limit.
     * <p>This defines how many commands will be processed before breaking receipt loop.
     *
     * @return Error limit.
     */
    public int getTransactionsLimit() {
        return Math.toIntExact(getLongProperty("transactionsLimit", 200L));
    }

    /**
     * Gets error limit.
     * <p>This defines how many syntax errors should be permitted before iterrupting the receipt.
     *
     * @return Error limit.
     */
    public int getErrorLimit() {
        return Math.toIntExact(getLongProperty("errorLimit", 3L));
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
     * Gets API port for client submission endpoint.
     *
     * @return Port number.
     */
    public int getApiPort() {
        return Math.toIntExact(getLongProperty("apiPort", 8090L));
    }

    /**
     * Gets storage config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getStorage() {
        // Attempt to lazy-load from storage.json5 if present and not already in map
        loadExternalIfAbsent("storage", "storage.json5", Map.class);
        return new BasicConfig(getMapProperty("storage"));
    }

    /**
     * Gets queue config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getQueue() {
        // Attempt to lazy-load from queue.json5 if present and not already in map
        loadExternalIfAbsent("queue", "queue.json5", Map.class);
        return new BasicConfig(getMapProperty("queue"));
    }

    /**
     * Gets relay config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getRelay() {
        // Attempt to lazy-load from relay.json5 if present and not already in map
        loadExternalIfAbsent("relay", "relay.json5", Map.class);
        return new BasicConfig(getMapProperty("relay"));
    }

    /**
     * Gets dovecot config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getDovecot() {
        // Attempt to lazy-load from dovecot.json5 if present and not already in map
        loadExternalIfAbsent("dovecot", "dovecot.json5", Map.class);
        return new BasicConfig(getMapProperty("dovecot"));
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
        // Attempt to lazy-load from users.json5 if present and not already in map
        loadExternalIfAbsent("users", "users.json5", List.class);

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
        // Attempt to lazy-load from scenarios.json5 if present and not already in map
        loadExternalIfAbsent("scenarios", "scenarios.json5", Map.class);

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
     * @param key      Root key to populate in the map.
     * @param filename File to read from the config directory.
     * @param clazz    Class to parse the JSON into (e.g., Map.class, List.class).
     */
    private void loadExternalIfAbsent(String key, String filename, Class<?> clazz) {
        if (!map.containsKey(key) && configDir != null) {
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
