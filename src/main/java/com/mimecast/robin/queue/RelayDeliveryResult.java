package com.mimecast.robin.queue;

import com.mimecast.robin.smtp.MessageEnvelope;

import java.util.List;

/**
 * Data class to hold delivery result information for relay sessions.
 * <p>Contains information about the outcome of processing a relay session's delivery attempt:
 * <ul>
 *   <li>Number of envelopes successfully delivered and removed</li>
 *   <li>Number of envelopes remaining (failed or partially failed)</li>
 *   <li>List of successfully delivered envelopes for cleanup</li>
 * </ul>
 */
public class RelayDeliveryResult {
    private final int removedCount;
    private final int remainingCount;
    private final List<MessageEnvelope> successfulEnvelopes;

    /**
     * Constructs a RelayDeliveryResult with the specified counts and envelopes.
     *
     * @param removedCount the number of envelopes successfully delivered and removed
     * @param remainingCount the number of envelopes remaining (failed or partially failed)
     * @param successfulEnvelopes the list of successfully delivered envelopes
     */
    public RelayDeliveryResult(int removedCount, int remainingCount, List<MessageEnvelope> successfulEnvelopes) {
        this.removedCount = removedCount;
        this.remainingCount = remainingCount;
        this.successfulEnvelopes = successfulEnvelopes;
    }

    /**
     * Gets the number of envelopes successfully delivered and removed.
     *
     * @return the number of removed envelopes
     */
    public int getRemovedCount() {
        return removedCount;
    }

    /**
     * Gets the number of envelopes remaining (failed or partially failed).
     *
     * @return the number of remaining envelopes
     */
    public int getRemainingCount() {
        return remainingCount;
    }

    /**
     * Gets the list of successfully delivered envelopes.
     *
     * @return the list of successful envelopes
     */
    public List<MessageEnvelope> getSuccessfulEnvelopes() {
        return successfulEnvelopes;
    }
}

