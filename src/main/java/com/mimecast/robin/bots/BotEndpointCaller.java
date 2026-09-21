package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.trust.PermissiveTrustManager;
import com.mimecast.robin.util.Magic;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared HTTP caller for bot endpoint POST requests.
 */
public final class BotEndpointCaller {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient INSECURE_HTTP_CLIENT = createInsecureClient();

    private static final Map<String, CircuitState> CIRCUITS = new ConcurrentHashMap<>();
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MILLIS = 30_000;

    private static final class CircuitState {
        private int consecutiveFailures;
        private long openUntilEpochMillis;

        synchronized boolean isOpen(long now) {
            return openUntilEpochMillis > now;
        }

        synchronized int consecutiveFailures() {
            return consecutiveFailures;
        }

        synchronized boolean recordFailure(long now) {
            consecutiveFailures++;
            if (consecutiveFailures < CIRCUIT_FAILURE_THRESHOLD || openUntilEpochMillis > now) {
                return false;
            }
            openUntilEpochMillis = now + CIRCUIT_OPEN_MILLIS;
            return true;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openUntilEpochMillis = 0;
        }
    }

    private BotEndpointCaller() {
        throw new IllegalStateException("Utility class");
    }

    private static OkHttpClient createInsecureClient() {
        try {
            var trustManager = new PermissiveTrustManager();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new SecureRandom());
            return HTTP_CLIENT.newBuilder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            return HTTP_CLIENT;
        }
    }

    /**
     * Sends JSON payload to a bot endpoint.
     *
     * @param payload       JSON payload.
     * @param connection    SMTP connection used for Magic replacement values.
     * @param botDefinition Bot definition holding endpoint and auth/header config.
     * @param botName       Bot name for logging.
     * @param log           Logger instance.
     */
    public static void postJson(
            String payload,
            Connection connection,
            BotConfig.BotDefinition botDefinition,
            String botName,
            Logger log
    ) {
        String endpoint = botDefinition != null ? botDefinition.getEndpoint() : "";
        boolean insecure = botDefinition != null && botDefinition.isInsecure();
        if (endpoint.isEmpty()) {
            log.warn("{} bot has no endpoint configured, cannot send report", botName);
            return;
        }

        CircuitState circuit = CIRCUITS.computeIfAbsent(endpoint, k -> new CircuitState());
        if (circuit.isOpen(System.currentTimeMillis())) {
            log.debug("Skipping {} report to {}: circuit open after {} consecutive failures",
                    botName, endpoint, circuit.consecutiveFailures());
            return;
        }

        RequestBody body = RequestBody.create(payload, JSON);
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json");

        addAuthentication(builder, connection, botDefinition);
        addCustomHeaders(builder, connection, botDefinition);

        OkHttpClient client = insecure ? INSECURE_HTTP_CLIENT : HTTP_CLIENT;
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "no body";
                log.error("Failed to send {} report to endpoint. Status: {} Response: {}",
                        botName, response.code(), responseBody);
                recordFailure(circuit, endpoint, botName, log);
            } else {
                log.debug("Successfully sent {} report to endpoint", botName);
                circuit.recordSuccess();
            }
        } catch (Exception e) {
            log.error("Error sending {} report to endpoint {}: {}: {}",
                    botName, endpoint, e.getClass().getSimpleName(), e.getMessage());
            recordFailure(circuit, endpoint, botName, log);
        }
    }

    private static void recordFailure(CircuitState circuit, String endpoint, String botName, Logger log) {
        long now = System.currentTimeMillis();
        if (circuit.recordFailure(now)) {
            log.warn("Opening circuit for {} endpoint {} after {} consecutive failures; pausing {}ms",
                    botName, endpoint, circuit.consecutiveFailures(), CIRCUIT_OPEN_MILLIS);
        }
    }

    private static void addAuthentication(
            Request.Builder builder,
            Connection connection,
            BotConfig.BotDefinition botDefinition
    ) {
        if (botDefinition == null) {
            return;
        }

        String authType = botDefinition.getAuthType();
        String authValue = Magic.magicReplace(botDefinition.getAuthValue(), connection.getSession());
        if ("basic".equalsIgnoreCase(authType) && !authValue.isEmpty()) {
            String encoded = Base64.getEncoder().encodeToString(authValue.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        } else if ("bearer".equalsIgnoreCase(authType) && !authValue.isEmpty()) {
            builder.header("Authorization", "Bearer " + authValue);
        }
    }

    private static void addCustomHeaders(
            Request.Builder builder,
            Connection connection,
            BotConfig.BotDefinition botDefinition
    ) {
        if (botDefinition == null) {
            return;
        }

        Map<String, String> headers = botDefinition.getHeaders();
        if (headers == null) {
            return;
        }

        for (Map.Entry<String, String> header : headers.entrySet()) {
            String value = Magic.magicReplace(header.getValue(), connection.getSession());
            builder.header(header.getKey(), value);
        }
    }
}
