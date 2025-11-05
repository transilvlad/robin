package com.mimecast.robin.smtp.webhook;

/**
 * Simple immutable container for webhook responses.
 */
public class WebhookResponse {
    private final int statusCode;
    private final String body;
    private final boolean success;

    /**
     * Constructs a new WebhookResponse.
     *
     * @param statusCode HTTP status code returned by the webhook call.
     * @param body       Raw response body returned by the webhook (may be empty but never null if set by caller).
     * @param success    Convenience flag indicating if the call is considered successful (typically 2xx range).
     */
    public WebhookResponse(int statusCode, String body, boolean success) {
        this.statusCode = statusCode;
        this.body = body;
        this.success = success;
    }

    /**
     * Gets the HTTP status code returned by the webhook.
     *
     * @return HTTP status code as int.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Gets the raw response body returned by the webhook.
     *
     * @return Response body string; may be empty if no body was provided.
     */
    public String getBody() {
        return body;
    }

    /**
     * Indicates whether the webhook call was considered successful.
     * This is derived at creation time (e.g. status code in 200-299 range).
     *
     * @return true if successful, false otherwise.
     */
    public boolean isSuccess() {
        return success;
    }
}
