package com.mimecast.robin.config.client;

import com.google.gson.Gson;
import com.mimecast.robin.config.ConfigFoundation;
import com.mimecast.robin.config.assertion.AssertConfig;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default client configuration container.
 *
 * <p>This class provides type safe access to default client configuration.
 * <p>Cases inherit defaults from here.
 * <p>This also houses routes that can be chosen in a case.
 *
 * @see ConfigFoundation
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ClientConfig extends ConfigFoundation {
    private final List<RouteConfig> routes = new ArrayList<>();

    // Configuration directory for lazy-loading external files (e.g., routes.json5).
    private String configDir;

    /**
     * Constructs a new ClientConfig instance.
     */
    public ClientConfig() {
        super();
        this.configDir = null;
    }

    /**
     * Constructs a new ClientConfig instance with configuration path.
     *
     * @param path Path to configuration file.
     * @throws IOException Unable to read file.
     */
    public ClientConfig(String path) throws IOException {
        super(path);
        this.configDir = new File(path).getParent();
        // Attempt to lazy-load routes from routes.json5 if present.
        loadExternalIfAbsent("routes", "routes.json5", List.class);
        // Populate routes list from config map
        getListProperty("routes").forEach(map -> routes.add(new RouteConfig((Map) map)));
    }

    /**
     * Gets MAIL property.
     *
     * @return MAIL string.
     */
    public String getMail() {
        return getStringProperty("mail");
    }

    /**
     * Gets RCPT property.
     *
     * @return RCPT string.
     */
    public List<String> getRcpt() {
        return getListProperty("rcpt");
    }

    /**
     * Gets assertion configuration.
     *
     * @return AssertConfig instance.
     */
    public AssertConfig getAssertions() {
        return new AssertConfig(getMapProperty("assertions"));
    }

    /**
     * Ensures routes are loaded into the in-memory list from the config map.
     */
    private void ensureRoutesLoaded() {
        if (routes.isEmpty()) {
            // Try to lazy-load from external file if not already in map.
            loadExternalIfAbsent("routes", "routes.json5", List.class);
            if (!getListProperty("routes").isEmpty()) {
                routes.clear();
                for (Object obj : getListProperty("routes")) {
                    routes.add(new RouteConfig((Map) obj));
                }
            }
        }
    }

    /**
     * Gets route if any.
     *
     * @param name Route name.
     * @return RouteConfig instance.
     */
    public RouteConfig getRoute(String name) {
        ensureRoutesLoaded();
        return routes.stream().filter(route -> route.getName().equals(name)).findFirst().orElse(null);
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
                    // Log at debug level to avoid noisy client init in environments without routes.json5.
                    // Use ConfigFoundation.log if available via inheritance.
                    log.debug("Failed to load {} from {}: {}", key, path, e.getMessage());
                }
            }
        }
    }
}
