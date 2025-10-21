package com.mimecast.robin.smtp.webhook;

import com.google.gson.*;
import com.mimecast.robin.config.server.WebhookConfig;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.util.GsonExclusionStrategy;
import com.mimecast.robin.util.Magic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Webhook caller service.
 *
 * <p>Handles HTTP calls to webhook endpoints with session payload as JSON.
 */
public class WebhookCaller {
    private static final Logger log = LogManager.getLogger(WebhookCaller.class);

    /**
     * Webhook response container.
     */
    public static class WebhookResponse {
        private final int statusCode;
        private final String body;
        private final boolean success;

        public WebhookResponse(int statusCode, String body, boolean success) {
            this.statusCode = statusCode;
            this.body = body;
            this.success = success;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    /**
     * Calls webhook with connection and verb data.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return WebhookResponse.
     */
    public static WebhookResponse call(WebhookConfig config, Connection connection, Verb verb) {
        if (!config.isEnabled()) {
            return new WebhookResponse(200, "", true);
        }

        if (config.isWaitForResponse()) {
            return callSync(config, connection, verb);
        } else {
            callAsync(config, connection, verb);
            return new WebhookResponse(200, "", true);
        }
    }

    /**
     * Calls webhook synchronously.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return WebhookResponse.
     */
    private static WebhookResponse callSync(WebhookConfig config, Connection connection, Verb verb) {
        try {
            return executeHttpRequest(config, connection, verb);
        } catch (Exception e) {
            log.error("Webhook call failed: {}", e.getMessage(), e);
            if (config.isIgnoreErrors()) {
                return new WebhookResponse(200, "", true);
            }
            return new WebhookResponse(500, e.getMessage(), false);
        }
    }

    /**
     * Calls webhook asynchronously.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     */
    private static void callAsync(WebhookConfig config, Connection connection, Verb verb) {
        CompletableFuture.runAsync(() -> {
            try {
                executeHttpRequest(config, connection, verb);
            } catch (Exception e) {
                if (!config.isIgnoreErrors()) {
                    log.error("Async webhook call failed: {}", e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Executes HTTP request to webhook.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return WebhookResponse.
     * @throws IOException If request fails.
     */
    private static WebhookResponse executeHttpRequest(WebhookConfig config, Connection connection, Verb verb) throws IOException {
        URI uri = URI.create(config.getUrl());
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            // Set method and timeout.
            conn.setRequestMethod(config.getMethod());
            conn.setConnectTimeout(config.getTimeout());
            conn.setReadTimeout(config.getTimeout());

            // Set headers.
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            // Add authentication.
            addAuthentication(conn, config, connection);

            // Add custom headers.
            Map<String, String> headers = config.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), Magic.magicReplace(header.getValue(), connection.getSession()));
                }
            }

            // Send payload for POST/PUT/PATCH.
            if ("POST".equalsIgnoreCase(config.getMethod()) ||
                    "PUT".equalsIgnoreCase(config.getMethod()) ||
                    "PATCH".equalsIgnoreCase(config.getMethod())) {

                conn.setDoOutput(true);
                String payload = buildPayload(config, connection, verb);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            // Get response.
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);

            return new WebhookResponse(statusCode, responseBody, statusCode >= 200 && statusCode < 300);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Adds authentication to connection.
     *
     * @param conn       HTTP connection.
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     */
    private static void addAuthentication(HttpURLConnection conn, WebhookConfig config, Connection connection) {
        String authType = config.getAuthType();
        String authValue = Magic.magicReplace(config.getAuthValue(), connection.getSession());

        if ("basic".equalsIgnoreCase(authType) && !authValue.isEmpty()) {
            String encoded = java.util.Base64.getEncoder().encodeToString(authValue.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        } else if ("bearer".equalsIgnoreCase(authType) && !authValue.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authValue);
        }
    }

    /**
     * Builds JSON payload.
     *
     * @param config     Webhook configuration.
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return JSON string.
     */
    private static String buildPayload(WebhookConfig config, Connection connection, Verb verb) {
        Gson gson = new GsonBuilder()
                // Exclude heavy, sensitive or irrelevant fields from Session and TransactionList.
                .setExclusionStrategies(new GsonExclusionStrategy())
                // Exclude sessionTransactionList field from Session to avoid heavy payloads.
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes fieldAttributes) {
                        return fieldAttributes.getName().equalsIgnoreCase("sessionTransactionList");
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> aClass) {
                        return true;
                    }
                })
                .create();

        JsonObject payload = new JsonObject();

        if (config.isIncludeSession() && connection.getSession() != null) {
            var session = connection.getSession().clone().clearEnvelopes();
            payload.add("session", gson.toJsonTree(session));
        }

        if (config.isIncludeEnvelope() && connection.getSession() != null && !connection.getSession().getEnvelopes().isEmpty()) {
            payload.add("envelope", gson.toJsonTree(connection.getSession().getEnvelopes().getLast()));
        }

        if (config.isIncludeVerb() && verb != null) {
            JsonObject verbObj = new JsonObject();
            verbObj.addProperty("command", verb.getCommand());
            verbObj.addProperty("key", verb.getKey());
            verbObj.addProperty("verb", verb.getVerb());
            payload.add("verb", verbObj);
        }

        return gson.toJson(payload);
    }

    /**
     * Reads HTTP response.
     *
     * @param conn       HTTP connection.
     * @param statusCode Status code.
     * @return Response body string.
     * @throws IOException If reading fails.
     */
    private static String readResponse(HttpURLConnection conn, int statusCode) throws IOException {
        BufferedReader reader;
        if (statusCode >= 200 && statusCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        return response.toString();
    }

    /**
     * Extracts SMTP response from webhook response body.
     *
     * @param body Response body.
     * @return SMTP response string or null.
     */
    public static String extractSmtpResponse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("smtpResponse")) {
                return json.get("smtpResponse").getAsString();
            }
        } catch (Exception e) {
            log.debug("Failed to parse webhook response as JSON: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Calls RAW webhook with email content as text/plain.
     * This is called after successful DATA processing.
     *
     * @param config   Webhook configuration.
     * @param filePath Path to email file.
     * @return WebhookResponse.
     */
    public static WebhookResponse callRaw(WebhookConfig config, String filePath) {
        if (!config.isEnabled() || config.getUrl().isEmpty()) {
            return new WebhookResponse(200, "", true);
        }

        if (config.isWaitForResponse()) {
            return callRawSync(config, filePath);
        } else {
            callRawAsync(config, filePath);
            return new WebhookResponse(200, "", true);
        }
    }

    /**
     * Calls RAW webhook synchronously.
     *
     * @param config   Webhook configuration.
     * @param filePath Path to email file.
     * @return WebhookResponse.
     */
    private static WebhookResponse callRawSync(WebhookConfig config, String filePath) {
        try {
            return executeRawHttpRequest(config, filePath);
        } catch (Exception e) {
            log.error("RAW webhook call failed: {}", e.getMessage(), e);
            if (config.isIgnoreErrors()) {
                return new WebhookResponse(200, "", true);
            }
            return new WebhookResponse(500, e.getMessage(), false);
        }
    }

    /**
     * Calls RAW webhook asynchronously.
     *
     * @param config   Webhook configuration.
     * @param filePath Path to email file.
     */
    private static void callRawAsync(WebhookConfig config, String filePath) {
        CompletableFuture.runAsync(() -> {
            try {
                executeRawHttpRequest(config, filePath);
            } catch (Exception e) {
                if (!config.isIgnoreErrors()) {
                    log.error("Async RAW webhook call failed: {}", e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Executes RAW HTTP request to webhook.
     *
     * @param config   Webhook configuration.
     * @param filePath Path to email file.
     * @return WebhookResponse.
     * @throws IOException If request fails.
     */
    private static WebhookResponse executeRawHttpRequest(WebhookConfig config, String filePath) throws IOException {
        URI uri = URI.create(config.getUrl());
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();

        try {
            // Set method and timeout.
            conn.setRequestMethod(config.getMethod());
            conn.setConnectTimeout(config.getTimeout());
            conn.setReadTimeout(config.getTimeout());

            // Set headers.
            if (config.isBase64()) {
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                conn.setRequestProperty("Content-Transfer-Encoding", "base64");
            } else {
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            }
            conn.setRequestProperty("Accept", "application/json");

            // Add authentication.
            addAuthentication(conn, config, null);

            // Add custom headers.
            Map<String, String> headers = config.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            // Send email content for POST/PUT/PATCH.
            if ("POST".equalsIgnoreCase(config.getMethod()) ||
                    "PUT".equalsIgnoreCase(config.getMethod()) ||
                    "PATCH".equalsIgnoreCase(config.getMethod())) {

                conn.setDoOutput(true);
                sendRawEmailContent(conn, filePath, config.isBase64());
            }

            // Get response.
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);

            log.info("RAW webhook called successfully: {} - Status: {}", config.getUrl(), statusCode);
            return new WebhookResponse(statusCode, responseBody, statusCode >= 200 && statusCode < 300);

        } finally {
            conn.disconnect();
        }
    }


    /**
     * Sends raw email content to webhook.
     *
     * @param conn     HTTP connection.
     * @param filePath Path to email file.
     * @param base64   Whether to base64 encode content.
     * @throws IOException If reading or writing fails.
     */
    private static void sendRawEmailContent(HttpURLConnection conn, String filePath, boolean base64) throws IOException {
        try (OutputStream os = conn.getOutputStream();
             java.io.FileInputStream fis = new java.io.FileInputStream(filePath)) {

            if (base64) {
                // Base64 encode the content.
                byte[] buffer = new byte[8192];
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                byte[] encoded = java.util.Base64.getEncoder().encode(baos.toByteArray());
                os.write(encoded);
            } else {
                // Send raw content.
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }
}
