package com.mimecast.robin.queue;

import java.io.Serializable;

/**
 * One queue-state mutation produced by a dequeue worker.
 *
 * @param <T> payload type
 */
public record QueueMutation<T extends Serializable>(
        QueueMutationType type,
        QueueItem<T> item,
        long nextAttemptAtEpochSeconds,
        String lastError
) {

    public static <T extends Serializable> QueueMutation<T> acknowledge(QueueItem<T> item) {
        return new QueueMutation<>(QueueMutationType.ACK, item, 0L, null);
    }

    public static <T extends Serializable> QueueMutation<T> reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        return new QueueMutation<>(QueueMutationType.RESCHEDULE, item, nextAttemptAtEpochSeconds, lastError);
    }

    public static <T extends Serializable> QueueMutation<T> dead(QueueItem<T> item, String lastError) {
        return new QueueMutation<>(QueueMutationType.DEAD, item, 0L, lastError);
    }
}
