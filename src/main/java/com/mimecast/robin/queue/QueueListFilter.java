package com.mimecast.robin.queue;

import java.util.EnumSet;
import java.util.Set;

/**
 * Queue listing filter.
 */
public class QueueListFilter {
    private Set<QueueItemState> states = EnumSet.of(QueueItemState.READY, QueueItemState.CLAIMED);
    private String protocol;
    private Integer minRetryCount;
    private Integer maxRetryCount;

    public static QueueListFilter activeOnly() {
        return new QueueListFilter();
    }

    public Set<QueueItemState> getStates() {
        return states;
    }

    public QueueListFilter setStates(Set<QueueItemState> states) {
        this.states = states;
        return this;
    }

    public String getProtocol() {
        return protocol;
    }

    public QueueListFilter setProtocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    public Integer getMinRetryCount() {
        return minRetryCount;
    }

    public QueueListFilter setMinRetryCount(Integer minRetryCount) {
        this.minRetryCount = minRetryCount;
        return this;
    }

    public Integer getMaxRetryCount() {
        return maxRetryCount;
    }

    public QueueListFilter setMaxRetryCount(Integer maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
        return this;
    }

    public boolean matches(QueueItem<?> item) {
        if (item == null) {
            return false;
        }
        if (states != null && !states.contains(item.getState())) {
            return false;
        }
        if (protocol != null && !protocol.equalsIgnoreCase(item.getProtocol())) {
            return false;
        }
        if (minRetryCount != null && item.getRetryCount() < minRetryCount) {
            return false;
        }
        if (maxRetryCount != null && item.getRetryCount() > maxRetryCount) {
            return false;
        }
        return true;
    }
}
