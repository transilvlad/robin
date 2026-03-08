package com.mimecast.robin.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayQueueServiceTest {

    @Test
    void calculateClaimBudgetCapsClaimsToRunnableCapacity() {
        assertEquals(0, RelayQueueService.calculateClaimBudget(64, 32, 32));
        assertEquals(7, RelayQueueService.calculateClaimBudget(64, 32, 25));
        assertEquals(32, RelayQueueService.calculateClaimBudget(250, 32, 0));
    }

    @Test
    void calculateClaimBudgetNeverReturnsNegativeValues() {
        assertEquals(0, RelayQueueService.calculateClaimBudget(64, 8, 20));
    }
}
