package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimRotationEvent;
import com.mimecast.robin.user.domain.DkimStrategy;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Orchestrates DKIM rotation phases and optional AUTO strategy scheduler.
 */
public class DkimRotationService {

    private static final Logger log = LogManager.getLogger(DkimRotationService.class);

    private final DkimKeyRepository repository;
    private final DkimLifecycleService lifecycleService;
    private final Clock clock;

    private volatile ScheduledExecutorService autoScheduler;

    public DkimRotationService(DkimKeyRepository repository, DkimLifecycleService lifecycleService) {
        this(repository, lifecycleService, Clock.systemUTC());
    }

    public DkimRotationService(DkimKeyRepository repository, DkimLifecycleService lifecycleService, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Marks a PENDING_PUBLISH key as DNS-published (pre-publish phase).
     */
    public DkimKey confirmPublished(long keyId, String triggeredBy, String notes, int prePublishDays) {
        DkimKey key = findOrThrow(keyId);
        if (key.getStatus() != DkimKeyStatus.PENDING_PUBLISH) {
            throw new IllegalStateException("Only PENDING_PUBLISH key can be confirmed as published");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        key.setPublishedAt(now);
        if (key.getStrategy() == DkimStrategy.AUTO) {
            key.setRotationScheduledAt(now.plusDays(prePublishDays));
        }
        repository.save(key);
        logEvent(key.getId(), "PUBLISHED", key.getStatus(), key.getStatus(), notes, triggeredBy);
        return key;
    }

    /**
     * Cutover phase: activate replacement key and move previous active key to ROTATING_OUT.
     */
    public DkimKey cutover(long keyId, String triggeredBy, String notes) {
        return lifecycleService.activate(keyId, triggeredBy, notes);
    }

    /**
     * Cleanup phase: retire a ROTATING_OUT key after overlap window.
     */
    public DkimKey cleanup(long keyId, String triggeredBy, String notes) {
        DkimKey key = findOrThrow(keyId);
        if (key.getStatus() != DkimKeyStatus.ROTATING_OUT) {
            throw new IllegalStateException("Only ROTATING_OUT key can be retired");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (key.getRetireAfter() != null && key.getRetireAfter().isAfter(now)) {
            throw new IllegalStateException("Key is not eligible for cleanup yet");
        }
        return lifecycleService.transition(keyId, DkimKeyStatus.RETIRED, triggeredBy, notes);
    }

    /**
     * Scheduler tick for AUTO strategy:
     * 1) activates due PENDING_PUBLISH keys with published DNS and due rotation timestamp
     * 2) retires due ROTATING_OUT keys after overlap window
     */
    public void runAutoRotationTick(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);

        for (String domain : domains) {
            List<DkimKey> keys = repository.findByDomain(domain);

            Stream<DkimKey> dueCutovers = keys.stream()
                    .filter(k -> k.getStrategy() == DkimStrategy.AUTO)
                    .filter(k -> k.getStatus() == DkimKeyStatus.PENDING_PUBLISH)
                    .filter(k -> k.getPublishedAt() != null)
                    .filter(k -> k.getRotationScheduledAt() != null && !k.getRotationScheduledAt().isAfter(now))
                    .sorted(Comparator.comparing(DkimKey::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

            dueCutovers.forEach(key -> {
                try {
                    cutover(key.getId(), "SCHEDULER", "AUTO strategy cutover");
                } catch (Exception e) {
                    log.warn("AUTO cutover failed for key {} (domain={}): {}", key.getId(), key.getDomain(), e.getMessage());
                }
            });

            List<DkimKey> refreshed = repository.findByDomain(domain);
            refreshed.stream()
                    .filter(k -> k.getStrategy() == DkimStrategy.AUTO)
                    .filter(k -> k.getStatus() == DkimKeyStatus.ROTATING_OUT)
                    .filter(k -> k.getRetireAfter() != null && !k.getRetireAfter().isAfter(now))
                    .forEach(key -> {
                        try {
                            cleanup(key.getId(), "SCHEDULER", "AUTO strategy cleanup");
                        } catch (Exception e) {
                            log.warn("AUTO cleanup failed for key {} (domain={}): {}", key.getId(), key.getDomain(), e.getMessage());
                        }
                    });
        }
    }

    /**
     * Starts periodic AUTO rotation processing.
     */
    public synchronized void startAutoRotationScheduler(Supplier<List<String>> domainSupplier,
                                                        long initialDelaySeconds,
                                                        long intervalSeconds) {
        Objects.requireNonNull(domainSupplier, "domainSupplier");
        if (autoScheduler != null) {
            return;
        }
        autoScheduler = Executors.newSingleThreadScheduledExecutor();
        autoScheduler.scheduleAtFixedRate(() -> {
            try {
                runAutoRotationTick(domainSupplier.get());
            } catch (Exception e) {
                log.error("AUTO rotation scheduler tick failed: {}", e.getMessage());
            }
        }, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Stops periodic AUTO rotation processing.
     */
    public synchronized void stopAutoRotationScheduler() {
        if (autoScheduler != null) {
            autoScheduler.shutdown();
            autoScheduler = null;
        }
    }

    public boolean isAutoRotationSchedulerRunning() {
        return autoScheduler != null && !autoScheduler.isShutdown();
    }

    private DkimKey findOrThrow(long keyId) {
        return repository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("DKIM key not found: " + keyId));
    }

    private void logEvent(Long keyId,
                          String eventType,
                          DkimKeyStatus oldStatus,
                          DkimKeyStatus newStatus,
                          String notes,
                          String triggeredBy) {
        DkimRotationEvent event = new DkimRotationEvent();
        event.setKeyId(keyId);
        event.setEventType(eventType);
        event.setOldStatus(oldStatus != null ? oldStatus.name() : null);
        event.setNewStatus(newStatus != null ? newStatus.name() : null);
        event.setNotes(notes);
        event.setTriggeredBy(triggeredBy != null ? triggeredBy : "USER");
        repository.logEvent(event);
    }
}
