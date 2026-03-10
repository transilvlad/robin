package com.mimecast.robin.queue;

/**
 * Mutation types applied after dequeue workers finish processing.
 */
public enum QueueMutationType {
    ACK,
    RESCHEDULE,
    DEAD
}
