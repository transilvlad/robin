package com.mimecast.robin.queue;

import java.nio.file.Path;
import java.util.List;

/**
 * Result returned by one dequeue worker, applied later by the queue committer.
 */
public record RelayQueueWorkResult(
        QueueMutation<RelaySession> mutation,
        List<RelaySession> newItems,
        List<Path> cleanupPaths
) {
    public RelayQueueWorkResult {
        newItems = newItems == null ? List.of() : List.copyOf(newItems);
        cleanupPaths = cleanupPaths == null ? List.of() : List.copyOf(cleanupPaths);
    }
}
