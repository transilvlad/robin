package com.mimecast.robin.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * HashiCorp Vault client utility for secrets management.
 *
 * <p>This utility provides methods to interact with HashiCorp Vault to fetch secrets
 * securely. It supports both KV v1 and KV v2 secret engines.
 *
 * <p>Example usage:
 * <pre>
 * VaultClient client = new VaultClient.Builder()
 *     .withAddress("https://vault.example.com:8200")
 *     .withToken("s.abc123xyz")
 *     .build();
 *
 * String secret = client.getSecret("secret/data/myapp/config", "password");
 * </pre>
 */
public class VaultClient {
    private static final Logger log = LogManager.getLogger(VaultClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int DEFAULT_TIMEOUT = 30;

    private final String vaultAddress;
    private final String vaultToken;
    private final String namespace;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final boolean enabled;

    /**
     * Constructs a new VaultClient instance.
     *
     * @param builder Builder instance with configuration.
     */
    private VaultClient(Builder builder) {
        this.vaultAddress = builder.vaultAddress;
        this.vaultToken = builder.vaultToken;
        this.namespace = builder.namespace;
        this.enabled = builder.enabled;
        this.gson = new Gson();

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(builder.connectTimeout, TimeUnit.SECONDS)
                .readTimeout(builder.readTimeout, TimeUnit.SECONDS)
                .writeTimeout(builder.writeTimeout, TimeUnit.SECONDS);

        if (builder.skipTlsVerification) {
            configureTrustAllCerts(clientBuilder);
        }

        this.httpClient = clientBuilder.build();
    }

    /**
     * Configure the HTTP client to trust all certificates.
     * WARNING: This should only be used in development environments.
     *
     * @param builder OkHttpClient.Builder to configure.
     */
    private void configureTrustAllCerts(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // Trust all clients
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            // Trust all servers
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);

            log.warn("TLS verification disabled for Vault client - use only in development!");
        } catch (Exception e) {
            log.error("Failed to configure trust all certificates", e);
        }
    }

    /**
     * Checks if Vault integration is enabled.
     *
     * @return true if enabled, false otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Fetches a secret from Vault.
     *
     * @param path Path to the secret (e.g., "secret/data/myapp/config" for KV v2).
     * @param key  Key within the secret to retrieve.
     * @return Secret value as string, or null if not found.
     * @throws VaultException if the request fails.
     */
    public String getSecret(String path, String key) throws VaultException {
        if (!enabled) {
            log.debug("Vault is disabled, skipping secret fetch for path: {}", path);
            return null;
        }

        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(key, "key must not be null");

        try {
            String url = vaultAddress + "/v1/" + path;
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("X-Vault-Token", vaultToken)
                    .get();

            if (namespace != null && !namespace.isEmpty()) {
                requestBuilder.header("X-Vault-Namespace", namespace);
            }

            Request request = requestBuilder.build();

            log.debug("Fetching secret from Vault: {}", path);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new VaultException("Vault request failed with status: " + response.code() +
                            ", message: " + response.message());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

                // Handle KV v2 structure (data.data.key)
                if (jsonResponse.has("data") && jsonResponse.getAsJsonObject("data").has("data")) {
                    JsonObject data = jsonResponse.getAsJsonObject("data").getAsJsonObject("data");
                    if (data.has(key)) {
                        String secret = data.get(key).getAsString();
                        log.debug("Successfully retrieved secret for key: {}", key);
                        return secret;
                    }
                }
                // Handle KV v1 structure (data.key)
                else if (jsonResponse.has("data")) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    if (data.has(key)) {
                        String secret = data.get(key).getAsString();
                        log.debug("Successfully retrieved secret for key: {}", key);
                        return secret;
                    }
                }

                log.warn("Key '{}' not found in Vault path: {}", key, path);
                return null;
            }
        } catch (IOException e) {
            throw new VaultException("Failed to fetch secret from Vault: " + e.getMessage());
        }
    }

    /**
     * Fetches all secrets from a given path.
     *
     * @param path Path to the secret.
     * @return Map of all key-value pairs in the secret, or empty map if not found.
     * @throws VaultException if the request fails.
     */
    public Map<String, String> getAllSecrets(String path) throws VaultException {
        if (!enabled) {
            log.debug("Vault is disabled, skipping secret fetch for path: {}", path);
            return new HashMap<>();
        }

        Objects.requireNonNull(path, "path must not be null");

        try {
            String url = vaultAddress + "/v1/" + path;
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("X-Vault-Token", vaultToken)
                    .get();

            if (namespace != null && !namespace.isEmpty()) {
                requestBuilder.header("X-Vault-Namespace", namespace);
            }

            Request request = requestBuilder.build();

            log.debug("Fetching all secrets from Vault: {}", path);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new VaultException("Vault request failed with status: " + response.code() +
                            ", message: " + response.message());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

                Map<String, String> secrets = new HashMap<>();

                // Handle KV v2 structure
                if (jsonResponse.has("data") && jsonResponse.getAsJsonObject("data").has("data")) {
                    JsonObject data = jsonResponse.getAsJsonObject("data").getAsJsonObject("data");
                    data.entrySet().forEach(entry ->
                        secrets.put(entry.getKey(), entry.getValue().getAsString())
                    );
                }
                // Handle KV v1 structure
                else if (jsonResponse.has("data")) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    data.entrySet().forEach(entry ->
                        secrets.put(entry.getKey(), entry.getValue().getAsString())
                    );
                }

                log.debug("Successfully retrieved {} secrets from path: {}", secrets.size(), path);
                return secrets;
            }
        } catch (IOException e) {
            throw new VaultException("Failed to fetch secrets from Vault: " + e.getMessage());
        }
    }

    /**
     * Writes a secret to Vault.
     *
     * @param path    Path where the secret will be stored.
     * @param secrets Map of key-value pairs to store.
     * @throws VaultException if the request fails.
     */
    public void writeSecret(String path, Map<String, String> secrets) throws VaultException {
        if (!enabled) {
            log.debug("Vault is disabled, skipping secret write for path: {}", path);
            return;
        }

        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(secrets, "secrets must not be null");

        try {
            String url = vaultAddress + "/v1/" + path;

            // Wrap secrets in "data" object for KV v2
            Map<String, Object> payload = new HashMap<>();
            payload.put("data", secrets);

            RequestBody body = RequestBody.create(gson.toJson(payload), JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("X-Vault-Token", vaultToken)
                    .post(body);

            if (namespace != null && !namespace.isEmpty()) {
                requestBuilder.header("X-Vault-Namespace", namespace);
            }

            Request request = requestBuilder.build();

            log.debug("Writing secret to Vault: {}", path);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new VaultException("Vault write request failed with status: " + response.code() +
                            ", message: " + response.message());
                }
                log.debug("Successfully wrote secret to path: {}", path);
            }
        } catch (IOException e) {
            throw new VaultException("Failed to write secret to Vault: " + e.getMessage());
        }
    }

    /**
     * Builder for VaultClient.
     */
    public static class Builder {
        private String vaultAddress;
        private String vaultToken;
        private String namespace;
        private boolean enabled = true;
        private boolean skipTlsVerification = false;
        private int connectTimeout = DEFAULT_TIMEOUT;
        private int readTimeout = DEFAULT_TIMEOUT;
        private int writeTimeout = DEFAULT_TIMEOUT;

        /**
         * Sets the Vault server address.
         *
         * @param address Vault server URL (e.g., "https://vault.example.com:8200").
         * @return Builder instance.
         */
        public Builder withAddress(String address) {
            this.vaultAddress = address;
            return this;
        }

        /**
         * Sets the Vault authentication token.
         *
         * @param token Vault token.
         * @return Builder instance.
         */
        public Builder withToken(String token) {
            this.vaultToken = token;
            return this;
        }

        /**
         * Sets the Vault namespace (for Vault Enterprise).
         *
         * @param namespace Vault namespace.
         * @return Builder instance.
         */
        public Builder withNamespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Enables or disables the Vault client.
         *
         * @param enabled true to enable, false to disable.
         * @return Builder instance.
         */
        public Builder withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Skip TLS certificate verification (for development only).
         *
         * @param skip true to skip verification.
         * @return Builder instance.
         */
        public Builder withSkipTlsVerification(boolean skip) {
            this.skipTlsVerification = skip;
            return this;
        }

        /**
         * Sets connection timeout.
         *
         * @param timeout Timeout in seconds.
         * @return Builder instance.
         */
        public Builder withConnectTimeout(int timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * Sets read timeout.
         *
         * @param timeout Timeout in seconds.
         * @return Builder instance.
         */
        public Builder withReadTimeout(int timeout) {
            this.readTimeout = timeout;
            return this;
        }

        /**
         * Sets write timeout.
         *
         * @param timeout Timeout in seconds.
         * @return Builder instance.
         */
        public Builder withWriteTimeout(int timeout) {
            this.writeTimeout = timeout;
            return this;
        }

        /**
         * Builds the VaultClient instance.
         *
         * @return VaultClient instance.
         * @throws IllegalStateException if required fields are missing.
         */
        public VaultClient build() {
            if (enabled) {
                Objects.requireNonNull(vaultAddress, "vaultAddress must not be null when enabled");
                Objects.requireNonNull(vaultToken, "vaultToken must not be null when enabled");
            }
            return new VaultClient(this);
        }
    }

    /**
     * Exception thrown when Vault operations fail.
     */
    public static class VaultException extends Exception {
        /**
         * Constructs a new VaultException.
         *
         * @param message Error message.
         */
        public VaultException(String message) {
            super(message);
        }

        /**
         * Constructs a new VaultException with cause.
         *
         * @param message Error message.
         * @param cause   Underlying cause.
         */
        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
