package com.mimecast.robin.config;

import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * General purpose configuration.
 *
 * <p>This provides access to a generic properties file.
 * <p>It will also read primitives from system properties with priority.
 */
public class Properties extends ConfigFoundation {

    /**
     * Constructs a new Properties instance.
     */
    public Properties() {
        super();
        this.map = concurrent(this.map);
    }

    /**
     * Constructs a new Properties instance with given file path.
     *
     * @param path File path.
     * @throws IOException Unable to read file.
     */
    public Properties(String path) throws IOException {
        super(path);
        this.map = concurrent(this.map);
    }

    /**
     * Wraps the backing map in a concurrent map.
     *
     * <p>The global properties instance is shared process wide. It is mutated at runtime
     * (ConfigLoader merges custom properties, properties auto reload replaces values)
     * while other threads iterate it, for example Magic.putConfiguredMagic when a Session
     * is constructed. Gson's LinkedTreeMap and HashMap are not thread safe and throw
     * ConcurrentModificationException in that situation.
     *
     * <p>Null values are dropped as ConcurrentHashMap does not permit them. A null valued
     * property is equivalent to an absent one for all callers of this class.
     *
     * @param source Source map, may be null.
     * @return Thread safe map.
     */
    private static Map<String, Object> concurrent(Map<String, Object> source) {
        Map<String, Object> target = new ConcurrentHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Object> entry : new HashMap<>(source).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    target.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return target;
    }

    /**
     * Check a property exists with system property check.
     *
     * @param name Property name.
     * @return Boolean.
     */
    @Override
    public boolean hasProperty(String name) {
        return super.hasProperty(name) || System.getProperty(name) != null;
    }

    /**
     * Gets String property with system property priority.
     *
     * @param name Property name.
     * @return String.
     */
    @Override
    public String getStringProperty(String name) {
        String sys = System.getProperty(name);
        return StringUtils.isNotBlank(sys) ? sys : super.getStringProperty(name, null);
    }

    /**
     * Gets String property with default.
     *
     * @param name Property name.
     * @param def  Default value.
     * @return String.
     */
    @Override
    public String getStringProperty(String name, String def) {
        return hasProperty(name) ? getStringProperty(name) : super.getStringProperty(name, def);
    }

    /**
     * Gets Long property with system property priority.
     *
     * @param name Property name.
     * @return Long.
     */
    @Override
    public Long getLongProperty(String name) {
        String sys = System.getProperty(name);
        return StringUtils.isNotBlank(sys) ? Long.valueOf(sys) : super.getLongProperty(name);
    }

    /**
     * Gets Boolean property with system property priority.
     *
     * @param name Property name.
     * @return Boolean.
     */
    @Override
    public Boolean getBooleanProperty(String name) {
        String sys = System.getProperty(name);
        return StringUtils.isNotBlank(sys) ? Boolean.valueOf(sys) : super.getBooleanProperty(name);
    }

    /**
     * Gets Locale property if set or default.
     *
     * @return Locale instance.
     */
    public Locale getLocale() {
        return LocaleUtils.toLocale(getStringProperty("locale", Locale.getDefault().toString()));
    }

    /**
     * Gets properties auto reload config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getPropertiesAutoReload() {
        return new BasicConfig(getMapProperty("propertiesAutoReload"));
    }

    /**
     * Gets server auto reload config.
     *
     * @return BasicConfig instance.
     */
    public BasicConfig getServerAutoReload() {
        return new BasicConfig(getMapProperty("serverAutoReload"));
    }
}
