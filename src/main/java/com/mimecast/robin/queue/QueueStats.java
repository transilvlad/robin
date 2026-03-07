package com.mimecast.robin.queue;

/**
 * Aggregate queue statistics.
 *
 * @param readyCount ready item count
 * @param claimedCount claimed item count
 * @param deadCount dead item count
 * @param totalCount active item count
 * @param oldestReadyAtEpochSeconds oldest ready-at value, or 0 when none
 * @param oldestClaimedAtEpochSeconds oldest claimed-until value, or 0 when none
 */
public record QueueStats(
        long readyCount,
        long claimedCount,
        long deadCount,
        long totalCount,
        long oldestReadyAtEpochSeconds,
        long oldestClaimedAtEpochSeconds
) {
}
