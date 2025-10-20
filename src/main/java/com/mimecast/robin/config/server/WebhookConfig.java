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
        return getBooleanProperty("includeEnvelopes", true);
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
     * Whether this is a RAW webhook (for DATA extension only).
     * RAW webhooks post the email content as text/plain instead of JSON.
     *
     * @return Boolean.
     */
    public boolean isRaw() {
        return getBooleanProperty("raw", false);
    }

    /**
     * Whether to base64 encode the RAW email content.
     *
     * @return Boolean.
     */
    public boolean isRawBase64() {
        return getBooleanProperty("rawBase64", false);
    }

    /**
     * Gets RAW webhook URL (separate from main webhook URL).
     *
     * @return RAW URL string.
     */
    public String getRawUrl() {
        return getStringProperty("rawUrl", "");
    }

    /**
     * Gets RAW webhook HTTP method.
     *
     * @return HTTP method string.
     */
    public String getRawMethod() {
        return getStringProperty("rawMethod", "POST");
    }

    /**
     * Gets RAW webhook timeout in milliseconds.
     *
     * @return Timeout value.
     */
    public int getRawTimeout() {
        return Math.toIntExact(getLongProperty("rawTimeout", 10000L));
    }

    /**
     * Whether to wait for RAW webhook response.
     *
     * @return Boolean.
     */
    public boolean isRawWaitForResponse() {
        return getBooleanProperty("rawWaitForResponse", false);
    }

    /**
     * Whether to ignore errors from RAW webhook.
     *
     * @return Boolean.
     */
    public boolean isRawIgnoreErrors() {
        return getBooleanProperty("rawIgnoreErrors", true);
    }

    /**
     * Gets RAW webhook authentication type.
     *
     * @return Auth type string.
     */
    public String getRawAuthType() {
        return getStringProperty("rawAuthType", "none");
    }

    /**
     * Gets RAW webhook authentication value.
     *
     * @return Auth value string.
     */
    public String getRawAuthValue() {
        return getStringProperty("rawAuthValue", "");
    }

    /**
     * Gets RAW webhook custom headers.
     *
     * @return Headers map.
     */
    public Map<String, String> getRawHeaders() {
        return (Map<String, String>) getMapProperty("rawHeaders");
    }
}
