package com.mimecast.robin.queue;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent queue item with scheduling and claim metadata.
 *
 * @param <T> payload type
 */
public class QueueItem<T extends Serializable> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String uid;
    private final long createdAtEpochSeconds;
    private long updatedAtEpochSeconds;
    private long nextAttemptAtEpochSeconds;
    private long claimedUntilEpochSeconds;
    private String claimOwner;
    private QueueItemState state;
    private int retryCount;
    private String protocol;
    private String sessionUid;
    private String lastError;
    private T payload;

    private QueueItem(String uid, long nowEpochSeconds, T payload) {
        this.uid = uid;
        this.createdAtEpochSeconds = nowEpochSeconds;
        this.updatedAtEpochSeconds = nowEpochSeconds;
        this.nextAttemptAtEpochSeconds = nowEpochSeconds;
        this.state = QueueItemState.READY;
        this.payload = payload;
    }

    /**
     * Creates a queue item using the relay session UID when available.
     */
    public static <T extends Serializable> QueueItem<T> ready(T payload) {
        long now = Instant.now().getEpochSecond();
        return new QueueItem<>(extractUid(payload), now, payload).syncFromPayload();
    }

    /**
     * Restores a persisted queue item using stored metadata and payload.
     */
    public static <T extends Serializable> QueueItem<T> restore(String uid, long createdAtEpochSeconds, T payload) {
        return new QueueItem<>(uid, createdAtEpochSeconds, payload).syncFromPayload();
    }

    private static <T extends Serializable> String extractUid(T payload) {
        if (payload instanceof RelaySession relaySession) {
            return relaySession.getUID();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Synchronizes metadata fields that are mirrored from the payload.
     */
    public QueueItem<T> syncFromPayload() {
        if (payload instanceof RelaySession relaySession) {
            this.retryCount = relaySession.getRetryCount();
            this.protocol = relaySession.getProtocol();
            this.sessionUid = relaySession.getSession() != null ? relaySession.getSession().getUID() : null;
        }
        return this;
    }

    public String getUid() {
        return uid;
    }

    public long getCreatedAtEpochSeconds() {
        return createdAtEpochSeconds;
    }

    public long getUpdatedAtEpochSeconds() {
        return updatedAtEpochSeconds;
    }

    public QueueItem<T> setUpdatedAtEpochSeconds(long updatedAtEpochSeconds) {
        this.updatedAtEpochSeconds = updatedAtEpochSeconds;
        return this;
    }

    public long getNextAttemptAtEpochSeconds() {
        return nextAttemptAtEpochSeconds;
    }

    public QueueItem<T> setNextAttemptAtEpochSeconds(long nextAttemptAtEpochSeconds) {
        this.nextAttemptAtEpochSeconds = nextAttemptAtEpochSeconds;
        return this;
    }

    public long getClaimedUntilEpochSeconds() {
        return claimedUntilEpochSeconds;
    }

    public QueueItem<T> setClaimedUntilEpochSeconds(long claimedUntilEpochSeconds) {
        this.claimedUntilEpochSeconds = claimedUntilEpochSeconds;
        return this;
    }

    public String getClaimOwner() {
        return claimOwner;
    }

    public QueueItem<T> setClaimOwner(String claimOwner) {
        this.claimOwner = claimOwner;
        return this;
    }

    public QueueItemState getState() {
        return state;
    }

    public QueueItem<T> setState(QueueItemState state) {
        this.state = state;
        return this;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public QueueItem<T> setRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    public String getProtocol() {
        return protocol;
    }

    public QueueItem<T> setProtocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    public String getSessionUid() {
        return sessionUid;
    }

    public QueueItem<T> setSessionUid(String sessionUid) {
        this.sessionUid = sessionUid;
        return this;
    }

    public String getLastError() {
        return lastError;
    }

    public QueueItem<T> setLastError(String lastError) {
        this.lastError = lastError;
        return this;
    }

    public T getPayload() {
        return payload;
    }

    public QueueItem<T> setPayload(T payload) {
        this.payload = payload;
        return this;
    }

    public boolean isActive() {
        return state == QueueItemState.READY || state == QueueItemState.CLAIMED;
    }

    public QueueItem<T> readyAt(long nextAttemptAtEpochSeconds) {
        long now = Instant.now().getEpochSecond();
        this.state = QueueItemState.READY;
        this.nextAttemptAtEpochSeconds = nextAttemptAtEpochSeconds;
        this.claimedUntilEpochSeconds = 0L;
        this.claimOwner = null;
        this.updatedAtEpochSeconds = now;
        return this;
    }

    public QueueItem<T> claim(String owner, long claimUntilEpochSeconds) {
        long now = Instant.now().getEpochSecond();
        this.state = QueueItemState.CLAIMED;
        this.claimOwner = owner;
        this.claimedUntilEpochSeconds = claimUntilEpochSeconds;
        this.updatedAtEpochSeconds = now;
        return this;
    }

    public QueueItem<T> dead(String error) {
        long now = Instant.now().getEpochSecond();
        this.state = QueueItemState.DEAD;
        this.lastError = error;
        this.claimOwner = null;
        this.claimedUntilEpochSeconds = 0L;
        this.updatedAtEpochSeconds = now;
        return this;
    }
}
