package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.session.Session;

import java.io.Serial;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Relay session.
 */
public class RelaySession implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Unique identifier for this relay session.
     * This is final and cannot be changed, unlike the session UID which can be duplicated.
     */
    private final String uid;

    /**
     * Session.
     */
    private final Session session;

    /**
     * Protocol. (ESMTP as default)
     */
    private String protocol = "ESMTP";

    /**
     * Mailbox (Only for DOVECOT-LDA).
     */
    private String mailbox;

    /**
     * IP pool key assigned at enqueue time. Persists across retries.
     */
    private String poolKey;

    /**
     * Retry count.
     */
    private int retryCount = 0;

    /**
     * Maximum retry count for this session.
     */
    private int maxRetryCount;

    /**
     * Session creation time (epoch seconds).
     */
    private final long createTime;

    /**
     * Last retry bump time (epoch seconds).
     */
    private long lastRetryTime = 0;

    /**
     * Constructs a new RelaySession instance.
     */
    public RelaySession(Session session) {
        this(session, UUID.randomUUID().toString(), Instant.now().getEpochSecond());
    }

    private RelaySession(Session session, String uid, long createTime) {
        this.uid = uid;
        this.createTime = createTime;
        this.session = session;

        // Default to relay config value.
        this.maxRetryCount = Math.toIntExact(
            Config.getServer().getRelay().getLongProperty("maxRetryCount", 30L)
        );
    }

    /**
     * Restores a relay session from persisted queue payload state.
     */
    public static RelaySession restore(Session session, String uid, String protocol, String mailbox, String poolKey,
                                       int retryCount, int maxRetryCount, long createTime, long lastRetryTime) {
        RelaySession relaySession = new RelaySession(session, uid, createTime);
        relaySession.protocol = protocol;
        relaySession.mailbox = mailbox;
        relaySession.poolKey = poolKey;
        relaySession.retryCount = retryCount;
        relaySession.maxRetryCount = maxRetryCount;
        relaySession.lastRetryTime = lastRetryTime;
        if (session != null) {
            session.setRetry(retryCount);
        }
        return relaySession;
    }

    /**
     * Gets the unique identifier for this relay session.
     *
     * @return String UID.
     */
    public String getUID() {
        return uid;
    }

    /**
     * Gets session.
     *
     * @return Session.
     */
    public Session getSession() {
        return session;
    }

    /**
     * Gets protocol.
     *
     * @return Protocol.
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Gets maximum retry count for this session.
     *
     * @return Maximum retry count.
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /**
     * Sets protocol and updates maxRetryCount if DOVECOT-LDA.
     *
     * @param protocol Protocol.
     * @return Self.
     */
    public RelaySession setProtocol(String protocol) {
        this.protocol = protocol;

        if ("dovecot-lda".equalsIgnoreCase(protocol)) {
            this.maxRetryCount = Config.getServer().getDovecot().getMaxRetryCount();
        } else if ("stalwart-direct".equalsIgnoreCase(protocol)) {
            this.maxRetryCount = Config.getServer().getStalwart().getMaxRetryCount();
        }
        return this;
    }

    /**
     * Gets mailbox.
     *
     * @return Mailbox.
     */
    public String getMailbox() {
        return mailbox;
    }

    /**
     * Sets mailbox.
     *
     * @param mailbox Mailbox.
     * @return Self.
     */
    public RelaySession setMailbox(String mailbox) {
        this.mailbox = mailbox;
        return this;
    }

    /**
     * Gets IP pool key.
     *
     * @return Pool key string.
     */
    public String getPoolKey() {
        return poolKey;
    }

    /**
     * Sets IP pool key.
     *
     * @param poolKey Pool key string.
     * @return Self.
     */
    public RelaySession setPoolKey(String poolKey) {
        this.poolKey = poolKey;
        return this;
    }

    /**
     * Bumps retry count.
     *
     * @return Self.
     */
    public RelaySession bumpRetryCount() {
        this.retryCount++;
        session.setRetry(retryCount);
        this.lastRetryTime = Instant.now().getEpochSecond();
        return this;
    }

    /**
     * Gets create time in epoch seconds.
     *
     * @return Long.
     */
    public long getCreateTime() {
        return createTime;
    }

    /**
     * Gets last retry time in epoch seconds.
     *
     * @return Long.
     */
    public long getLastRetryTime() {
        return lastRetryTime;
    }

    /**
     * Gets last retry date as formatted string.
     *
     * @return String.
     */
    public String getLastRetryDate() {
        // LastRetryTime is stored as epoch seconds; Date expects milliseconds.
        return new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Config.getProperties().getLocale())
                .format(new Date(lastRetryTime * 1000L));
    }

    /**
     * Gets retry count.
     *
     * @return Integer retry count.
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Gets rejection.
     *
     * @return String.
     */
    public String getRejection() {
        return session.getSessionTransactionList().getEnvelopes().getLast().getErrors().getLast().getResponse();
    }

    /**
     * Implements equality check by relay session UID.
     *
     * @return Boolean.
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof RelaySession && Objects.equals(uid, ((RelaySession) obj).getUID());
    }

    /**
     * Generates hash code based on relay session UID.
     *
     * @return Hash code.
     */
    @Override
    public int hashCode() {
        return uid != null ? uid.hashCode() : 0;
    }
}
