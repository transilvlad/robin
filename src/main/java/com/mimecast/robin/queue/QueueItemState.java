package com.mimecast.robin.queue;

/**
 * Queue item lifecycle states.
 */
public enum QueueItemState {
    READY,
    CLAIMED,
    DEAD
}
