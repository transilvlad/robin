/**
 * Manages the sending of SMTP-related events to webhook endpoints.
 *
 * <p>The webhook feature allows you to intercept SMTP extension processing and call external HTTP endpoints before the extension is processed.
 * <br>This enables custom validation, logging, policy enforcement, and integration with external systems.
 *
 * <p>Webhooks are configured in `cfg/webhooks.json5`. Each SMTP extension can have its own webhook configuration.
 */
package com.mimecast.robin.smtp.webhook;

