package com.mimecast.robin.queue;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class DestinationConcurrencyTest {
    @BeforeEach void init() throws Exception { ConfigLoader.load("src/test/resources/cfg/"); }
    @AfterEach void restore() throws Exception { ConfigLoader.load("src/test/resources/cfg/"); }

    private QueueItem<RelaySession> item(String protocol, String... hosts) {
        return QueueItem.ready(new RelaySession(new Session().setMx(List.of(hosts)).setPort(25)).setProtocol(protocol));
    }

    private RelayDequeue successfulWorker(AtomicInteger calls) {
        return new RelayDequeue(null) {
            @Override public RelayQueueWorkResult processClaimedItem(QueueItem<RelaySession> item, long now) {
                calls.incrementAndGet();
                return new RelayQueueWorkResult(QueueMutation.acknowledge(item), List.of(), List.of());
            }
        };
    }

    @Test
    void fallbackEndpointsAreReservedAtomicallyAndReleasedIdempotently() {
        var limits = new DestinationConcurrency(1, 5);
        var first = limits.tryAcquire(item("esmtp", "MX-A.EXAMPLE.", "shared.example"), 1000, 0).orElseThrow();
        assertTrue(limits.tryAcquire(item("lmtp", "mx-b.example", "SHARED.EXAMPLE."), 1000, 0).isEmpty());
        try (var second = limits.tryAcquire(item("esmtp", "mx-b.example"), 1000, 0).orElseThrow()) {
            assertEquals(3, limits.activeDestinations(), "Rejected multi-key admission must not reserve mx-b");
        }
        assertTrue(limits.tryAcquire(item("esmtp", "mx-a.example"), 1000, 0).isEmpty());
        first.close();
        first.close();
        assertEquals(0, limits.activeDestinations());
    }

    @Test
    void concurrentReservationsCannotBothOwnSharedFallbackEndpoint() throws Exception {
        var limits = new DestinationConcurrency(1, 5);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return limits.tryAcquire(item("esmtp", "a", "shared"), 1000, 0); });
            var b = executor.submit(() -> { start.await(); return limits.tryAcquire(item("esmtp", "b", "shared"), 1000, 0); });
            start.countDown();
            var first = a.get(5, TimeUnit.SECONDS);
            var second = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(first.isPresent(), second.isPresent());
            first.ifPresent(DestinationConcurrency.Lease::close);
            second.ifPresent(DestinationConcurrency.Lease::close);
            assertEquals(0, limits.activeDestinations());
        }
    }

    @Test
    void hotDomainDefersWithoutBlockingOtherDomainWorker() throws Exception {
        var limits = new DestinationConcurrency(1, 5);
        var hot = item("mx", "hot.example");
        var calls = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor();
             var blocked = limits.tryAcquire(hot, 1000, 0).orElseThrow()) {
            var completion = new ExecutorCompletionService<RelayQueueWorkResult>(executor);
            var deferred = RelayQueueService.dispatchClaimedItem(completion, limits, successfulWorker(calls), hot, 1000, 0);
            assertEquals(QueueMutationType.RESCHEDULE, deferred.orElseThrow().mutation().type());
            assertEquals(1005, deferred.orElseThrow().mutation().nextAttemptAtEpochSeconds());
            assertTrue(RelayQueueService.dispatchClaimedItem(completion, limits, successfulWorker(calls),
                    item("mx", "other.example"), 1000, 0).isEmpty());
            var result = completion.poll(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(QueueMutationType.ACK, result.get().mutation().type());
            assertEquals(1, calls.get());
            assertEquals(1, limits.activeDestinations());
        }
    }

    @Test
    void saturatedDeferralPreservesDurableAgeAndRetryCountsAndStopsAtExactExpiry() {
        var limits = new DestinationConcurrency(1, 5);
        long now = 1000;
        var relay = RelaySession.restore(new Session().setMx(List.of("hot.example")), "aged", "mx",
                null, null, 3, 100, 900, 900);
        var aged = QueueItem.restore("aged", now - 59, relay);
        var database = new InMemoryQueueDatabase<RelaySession>();
        database.enqueue(aged);
        try (var occupied = limits.tryAcquire(item("mx", "hot.example"), now, 0).orElseThrow()) {
            var claimed = database.claimReady(1, now, "test", now + 100).getFirst();
            assertTrue(limits.tryAcquire(claimed, now, 60).isEmpty());
            var deferred = limits.defer(claimed, now, 60, "Destination concurrency limit");
            assertEquals(now + 1, deferred.nextAttemptAtEpochSeconds());
            database.applyMutations(new QueueMutationBatch<>(List.of(deferred), List.of()));
            var stored = database.getByUID("aged");
            assertEquals(now - 59, stored.getCreatedAtEpochSeconds());
            assertEquals(3, stored.getRetryCount());
            assertEquals(3, stored.getPayload().getRetryCount());
            assertNull(stored.getClaimOwner());
            assertEquals(QueueItemState.READY, stored.getState());
            assertTrue(database.claimReady(1, now, "test", now + 100).isEmpty());
            var expired = database.claimReady(1, now + 1, "test", now + 100).getFirst();
            try (var terminal = limits.tryAcquire(expired, now + 1, 60).orElseThrow()) {
                assertEquals(1, limits.activeDestinations(), "Expired work must not reserve the saturated destination");
                assertThrows(IllegalStateException.class, () -> limits.defer(expired, now + 1, 60, "invalid"));
            }
        }
        database.close();
    }

    @Test
    void submissionRejectionReleasesLeaseAndReturnsDurableDeferral() {
        var limits = new DestinationConcurrency(1, 5);
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var calls = new AtomicInteger();
        var result = RelayQueueService.dispatchClaimedItem(new ExecutorCompletionService<>(executor),
                limits, successfulWorker(calls), item("mx", "hot.example"), 1000, 0).orElseThrow();
        assertEquals(QueueMutationType.RESCHEDULE, result.mutation().type());
        assertEquals(0, calls.get());
        assertEquals(0, limits.activeDestinations());
    }

    @Test
    void rejectedAlreadyExpiredWorkProcessesTerminalOutcomeInsteadOfRescheduling() {
        var limits = new DestinationConcurrency(1, 5);
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var calls = new AtomicInteger();
        var expired = item("mx", "hot.example");
        long now = expired.getCreatedAtEpochSeconds() + 60;
        try (var occupied = limits.tryAcquire(item("mx", "hot.example"), now, 0).orElseThrow()) {
            var result = RelayQueueService.dispatchClaimedItem(new ExecutorCompletionService<>(executor),
                    limits, successfulWorker(calls), expired, now, 60).orElseThrow();
            assertEquals(QueueMutationType.DEAD, result.mutation().type());
            assertEquals(0, calls.get());
            assertEquals(1, limits.activeDestinations());
        }
    }

    @Test
    void alreadyExpiredDispatchNeverReentersDeliveryEvenIfAgePolicyChanges() throws Exception {
        var limits = new DestinationConcurrency(1, 5);
        var expired = item("mx", "hot.example");
        long now = expired.getCreatedAtEpochSeconds() + 60;
        Config.getServer().getMap().put("queue", Map.of("retry", Map.of("maxAgeSeconds", 0L)));
        var dequeue = new RelayDequeue(null) {
            @Override public RelayQueueWorkResult processClaimedItem(QueueItem<RelaySession> item, long time) {
                throw new AssertionError("Terminal dispatch must not return to delivery or reread expiry policy");
            }
        };
        try (var executor = Executors.newSingleThreadExecutor();
             var occupied = limits.tryAcquire(item("mx", "hot.example"), now, 0).orElseThrow()) {
            var completion = new ExecutorCompletionService<RelayQueueWorkResult>(executor);
            assertTrue(RelayQueueService.dispatchClaimedItem(completion, limits, dequeue, expired, now, 60).isEmpty());
            var result = completion.poll(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(QueueMutationType.DEAD, result.get().mutation().type());
            assertEquals(1, limits.activeDestinations());
        }
    }

    @Test
    void workerExceptionAlwaysReleasesLease() throws Exception {
        var limits = new DestinationConcurrency(1, 5);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var completion = new ExecutorCompletionService<RelayQueueWorkResult>(executor);
            var failing = new RelayDequeue(null) {
                @Override public RelayQueueWorkResult processClaimedItem(QueueItem<RelaySession> item, long now) {
                    throw new IllegalStateException("Synthetic worker failure");
                }
            };
            assertTrue(RelayQueueService.dispatchClaimedItem(completion, limits, failing,
                    item("mx", "hot.example"), 1000, 0).isEmpty());
            var result = completion.poll(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertThrows(java.util.concurrent.ExecutionException.class, result::get);
            assertEquals(0, limits.activeDestinations());
        }
    }

    @Test
    void offPolicyDoesNotInspectOrReserveDestinationsAndMalformedLimitsAreRejected() {
        var limits = new DestinationConcurrency(0, 5);
        var noDestination = item("mx", "hot");
        noDestination.getPayload().getSession().setMx(null);
        try (var a = limits.tryAcquire(noDestination, 1000, 0).orElseThrow();
             var b = limits.tryAcquire(item("mx", "hot"), 1000, 0).orElseThrow()) {
            assertEquals(0, limits.activeDestinations());
        }
        assertThrows(IllegalArgumentException.class, () -> new DestinationConcurrency(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new DestinationConcurrency(1, 0));
        Config.getServer().getMap().put("queue", Map.of("maxConcurrentPerDestination", 0.5));
        assertThrows(IllegalArgumentException.class, DestinationConcurrency::fromConfig);
    }

    @Test
    void unresolvedLegacyMetadataIsGroupedWithoutBlockingExistingDeliveryValidation() {
        var limits = new DestinationConcurrency(1, 5);
        var absent = item("mx", "hot");
        absent.getPayload().getSession().setMx(null);
        var invalid = item("mx", "hot");
        invalid.getPayload().getSession().setMx(java.util.Collections.singletonList(null));
        try (var admitted = limits.tryAcquire(absent, 1000, 0).orElseThrow()) {
            assertTrue(limits.tryAcquire(invalid, 1000, 0).isEmpty());
            assertEquals(1, limits.activeDestinations());
        }
        assertEquals(0, limits.activeDestinations());
    }
}
