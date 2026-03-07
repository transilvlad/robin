package com.mimecast.robin.queue;

import java.io.Serializable;
import java.util.List;

/**
 * Paged queue listing.
 *
 * @param <T> payload type
 * @param total total matching items
 * @param items page items
 */
public record QueuePage<T extends Serializable>(long total, List<QueueItem<T>> items) {
}
