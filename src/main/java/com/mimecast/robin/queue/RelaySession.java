package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.session.Session;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Relay session.
 */
public class RelaySession implements Serializable {

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
     * File path.
     */
    private String filePath;

    /**
     * Retry count.
     */
    private int retryCount = 0;

    /**
     * Session creation time.
     */
    private long createTime = 0;

    /**
     * Last retry bump time.
     */
    private long lastRetryTime = 0;

    /**
     * Constructs a new DovecotSession instance.
     */
    public RelaySession(Session session) {
        this.session = session;
        this.createTime = (int) (System.currentTimeMillis() / 1000L);
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
     * Sets protocol.
     *
     * @param protocol Protocol.
     * @return Self.
     */
    public RelaySession setProtocol(String protocol) {
        this.protocol = protocol;
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
     * Bumps retry count.
     *
     * @return Self.
     */
    public RelaySession bumpRetryCount() {
        this.retryCount++;
        this.lastRetryTime = (int) (System.currentTimeMillis() / 1000L);
        return this;
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
        return new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Config.getProperties().getLocale()).format(new Date(lastRetryTime));
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
        return session.getSessionTransactionList().getErrors().get(0).getResponse();
    }

    /**
     * Implements equality check by session UID.
     *
     * @return Boolean.
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof RelaySession && session.getUID().equals(((RelaySession) obj).getSession().getUID());
    }
}
