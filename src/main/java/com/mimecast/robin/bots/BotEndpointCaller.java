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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared HTTP caller for bot endpoint POST requests.
 * <p>Requests that fail in a way that may succeed later (connection errors, timeouts, HTTP 408, 429 and
 * 5xx) and requests skipped while an endpoint's circuit is open are handed to {@link BotRetrySpool}
 * when it is running. Other failures are logged and dropped.
 */
public final class BotEndpointCaller {
    private static final Logger retryLog = LogManager.getLogger(BotRetrySpool.class);
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

    /**
     * Result of a single delivery attempt.
     */
    enum Result {
        /** The endpoint accepted the request. */
        DELIVERED,
        /** The attempt failed and may succeed later. */
        RETRY,
        /** The endpoint refused the request; sending it again will not help. */
        REJECTED,
        /** Not attempted because the endpoint's circuit is open. */
        DEFERRED
    }

    /**
     * Delivery attempt outcome.
     *
     * @param result Result.
     * @param error  Failure description, or null when delivered.
     */
    record Outcome(Result result, String error) {
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

        Map<String, String> headers = new LinkedHashMap<>();
        addAuthentication(headers, connection, botDefinition);
        addCustomHeaders(headers, connection, botDefinition);

        CircuitState circuit = CIRCUITS.computeIfAbsent(endpoint, k -> new CircuitState());
        if (circuit.isOpen(System.currentTimeMillis())) {
            String error = "circuit open after " + circuit.consecutiveFailures() + " consecutive failures";
            if (queueForRetry(botName, endpoint, insecure, headers, payload, error)) {
                log.debug("Queued {} report to {} for retry: {}", botName, endpoint, error);
            } else {
                log.debug("Skipping {} report to {}: {}", botName, endpoint, error);
            }
            return;
        }

        Outcome outcome = send(endpoint, insecure, headers, payload);
        if (outcome.result() == Result.DELIVERED) {
            log.debug("Successfully sent {} report to endpoint", botName);
            circuit.recordSuccess();
            return;
        }

        recordFailure(circuit, endpoint, botName, log);
        if (outcome.result() == Result.RETRY
                && queueForRetry(botName, endpoint, insecure, headers, payload, outcome.error())) {
            log.warn("Failed to send {} report to endpoint {}, queued for retry: {}",
                    botName, endpoint, outcome.error());
        } else {
            log.error("Failed to send {} report to endpoint {}: {}", botName, endpoint, outcome.error());
        }
    }

    /**
     * Sends a stored request again, honouring the endpoint's circuit.
     *
     * @param entry Stored request.
     * @return Delivery outcome.
     */
    static Outcome deliver(BotRetryEntry entry) {
        String endpoint = entry.getEndpoint();
        CircuitState circuit = CIRCUITS.computeIfAbsent(endpoint, k -> new CircuitState());
        if (circuit.isOpen(System.currentTimeMillis())) {
            return new Outcome(Result.DEFERRED, "circuit open");
        }
        Outcome outcome = send(endpoint, entry.isInsecure(), entry.getHeaders(), entry.getPayload());
        if (outcome.result() == Result.DELIVERED) {
            circuit.recordSuccess();
        } else {
            recordFailure(circuit, endpoint, entry.getBotName(), retryLog);
        }
        return outcome;
    }

    private static boolean queueForRetry(String botName, String endpoint, boolean insecure,
                                         Map<String, String> headers, String payload, String error) {
        BotRetrySpool spool = BotRetrySpool.getInstance();
        return spool != null && spool.enqueue(botName, endpoint, insecure, headers, payload, error);
    }

    private static Outcome send(String endpoint, boolean insecure, Map<String, String> headers, String payload) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(payload, JSON))
                .addHeader("Content-Type", "application/json");
        headers.forEach(builder::header);

        OkHttpClient client = insecure ? INSECURE_HTTP_CLIENT : HTTP_CLIENT;
        try (Response response = client.newCall(builder.build()).execute()) {
            if (response.isSuccessful()) {
                return new Outcome(Result.DELIVERED, null);
            }
            String responseBody = response.body() != null ? response.body().string() : "no body";
            String error = "Status: " + response.code() + " Response: " + responseBody;
            return new Outcome(isRetryableStatus(response.code()) ? Result.RETRY : Result.REJECTED, error);
        } catch (IOException e) {
            return new Outcome(Result.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            return new Outcome(Result.REJECTED, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Whether an HTTP status may succeed if the request is sent again.
     *
     * @param code HTTP status code.
     * @return True for 408, 429 and 5xx.
     */
    static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private static void recordFailure(CircuitState circuit, String endpoint, String botName, Logger log) {
        long now = System.currentTimeMillis();
        if (circuit.recordFailure(now)) {
            log.warn("Opening circuit for {} endpoint {} after {} consecutive failures; pausing {}ms",
                    botName, endpoint, circuit.consecutiveFailures(), CIRCUIT_OPEN_MILLIS);
        }
    }

    private static void addAuthentication(
            Map<String, String> headers,
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
            headers.put("Authorization", "Basic " + encoded);
        } else if ("bearer".equalsIgnoreCase(authType) && !authValue.isEmpty()) {
            headers.put("Authorization", "Bearer " + authValue);
        }
    }

    private static void addCustomHeaders(
            Map<String, String> headers,
            Connection connection,
            BotConfig.BotDefinition botDefinition
    ) {
        if (botDefinition == null) {
            return;
        }

        Map<String, String> configured = botDefinition.getHeaders();
        if (configured == null) {
            return;
        }

        for (Map.Entry<String, String> header : configured.entrySet()) {
            String value = Magic.magicReplace(header.getValue(), connection.getSession());
            headers.put(header.getKey(), value);
        }
    }
}
