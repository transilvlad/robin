package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

import java.util.Map;

/**
 * Webhook configuration.
 *
 * <p>This class provides type safe access to webhook configuration for individual extensions.
 */
@SuppressWarnings("unchecked")
public class WebhookConfig extends BasicConfig {

    /**
     * Constructs a new WebhookConfig instance with given map.
     *
     * @param map Configuration map.
     */
    public WebhookConfig(Map map) {
        super(map);
    }

    /**
     * Gets webhook URL.
     *
     * @return URL string.
     */
    public String getUrl() {
        return getStringProperty("url", "");
    }

    /**
     * Gets HTTP method (GET, POST, etc.).
     *
     * @return HTTP method string.
     */
    public String getMethod() {
        return getStringProperty("method", "POST");
    }

    /**
     * Gets timeout in milliseconds.
     *
     * @return Timeout value.
     */
    public int getTimeout() {
        return Math.toIntExact(getLongProperty("timeout", 5000L));
    }

    /**
     * Whether to wait for webhook response.
     *
     * @return Boolean.
     */
    public boolean isWaitForResponse() {
        return getBooleanProperty("waitForResponse", true);
    }

    /**
     * Whether to ignore errors from webhook.
     *
     * @return Boolean.
     */
    public boolean isIgnoreErrors() {
        return getBooleanProperty("ignoreErrors", false);
    }

    /**
     * Gets direction filter (inbound, outbound, both).
     * Returns null if not configured or defaults to "both".
     *
     * @return Direction string: "inbound", "outbound", or "both".
     */
    public String getDirection() {
        return getStringProperty("direction", "both");
    }

    /**
     * Gets custom headers map.
     *
     * @return Headers map.
     */
    public Map<String, String> getHeaders() {
        return (Map<String, String>) getMapProperty("headers");
    }

    /**
     * Whether webhook is enabled.
     *
     * @return Boolean.
     */
    public boolean isEnabled() {
        return getBooleanProperty("enabled", false);
    }

    /**
     * Gets authentication type (none, basic, bearer).
     *
     * @return Auth type string.
     */
    public String getAuthType() {
        return getStringProperty("authType", "none");
    }

    /**
     * Gets authentication token or credentials.
     *
     * @return Auth value string.
     */
    public String getAuthValue() {
        return getStringProperty("authValue", "");
    }

    /**
     * Whether to include session data in payload.
     *
     * @return Boolean.
     */
    public boolean isIncludeSession() {
        return getBooleanProperty("includeSession", true);
    }

    /**
     * Whether to include envelope data in payload.
     *
     * @return Boolean.
     */
    public boolean isIncludeEnvelope() {
        return getBooleanProperty("includeEnvelope", true);
    }

    /**
     * Whether to include verb data in payload.
     *
     * @return Boolean.
     */
    public boolean isIncludeVerb() {
        return getBooleanProperty("includeVerb", true);
    }

    /**
     * Whether to base64 encode the RAW email content.
     *
     * @return Boolean.
     */
    public boolean isBase64() {
        return getBooleanProperty("base64", false);
    }
}
