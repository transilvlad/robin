package com.mimecast.robin.bots;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A report bot endpoint request waiting to be sent again.
 * <p>Stored as one JSON file per request by {@link BotRetrySpool}. Headers are kept as they were sent,
 * since Magic values in {@code authValue} and {@code headers} come from the SMTP session that delivered
 * the report and are not available when the request is retried.
 */
public class BotRetryEntry {
    private String uid;
    private String botName;
    private String endpoint;
    private boolean insecure;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String payload;
    private int retryCount;
    private long firstFailedAt;
    private long nextAttemptAt;
    private String lastError;

    public String getUid() {
        return uid;
    }

    public BotRetryEntry setUid(String uid) {
        this.uid = uid;
        return this;
    }

    public String getBotName() {
        return botName;
    }

    public BotRetryEntry setBotName(String botName) {
        this.botName = botName;
        return this;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public BotRetryEntry setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public BotRetryEntry setInsecure(boolean insecure) {
        this.insecure = insecure;
        return this;
    }

    public Map<String, String> getHeaders() {
        return headers != null ? headers : Map.of();
    }

    public BotRetryEntry setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        return this;
    }

    public String getPayload() {
        return payload;
    }

    public BotRetryEntry setPayload(String payload) {
        this.payload = payload;
        return this;
    }

    /**
     * Gets the number of retries scheduled so far.
     *
     * @return Retry count.
     */
    public int getRetryCount() {
        return retryCount;
    }

    public BotRetryEntry setRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    /**
     * Gets the time of the first failed attempt in epoch seconds.
     *
     * @return Epoch seconds.
     */
    public long getFirstFailedAt() {
        return firstFailedAt;
    }

    public BotRetryEntry setFirstFailedAt(long firstFailedAt) {
        this.firstFailedAt = firstFailedAt;
        return this;
    }

    /**
     * Gets the time of the next attempt in epoch seconds.
     *
     * @return Epoch seconds.
     */
    public long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public BotRetryEntry setNextAttemptAt(long nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
        return this;
    }

    public String getLastError() {
        return lastError;
    }

    public BotRetryEntry setLastError(String lastError) {
        this.lastError = lastError;
        return this;
    }
}
