package com.mimecast.robin.smtp.webhook;

import com.mimecast.robin.config.server.WebhookConfig;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.verb.Verb;

/**
 * Interface for webhook caller implementations.
 *
 * <p>Allows custom webhook handling via {@code Factories.setWebhookCaller()}.
 */
public interface WebhookCallerInterface {

    /**
     * Calls webhook with connection and verb data.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return WebhookResponse.
     */
    WebhookResponse call(WebhookConfig config, Connection connection, Verb verb);

    /**
     * Calls RAW webhook with email content as text/plain.
     *
     * @param config     Webhook configuration.
     * @param filePath   Path to email file.
     * @param connection Connection instance.
     * @return WebhookResponse.
     */
    WebhookResponse callRaw(WebhookConfig config, String filePath, Connection connection);

    /**
     * Extracts SMTP response from webhook response body.
     *
     * @param body Response body.
     * @return SMTP response string or null.
     */
    String extractSmtpResponse(String body);
}
