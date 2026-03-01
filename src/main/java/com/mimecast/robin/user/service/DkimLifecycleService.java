package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimRotationEvent;
import com.mimecast.robin.user.repository.DkimKeyRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Enforces DKIM key lifecycle transitions and writes audit events.
 */
public class DkimLifecycleService {

    private final DkimKeyRepository repository;
    private final Clock clock;
    private final int overlapDays;

    public DkimLifecycleService(DkimKeyRepository repository) {
        this(repository, Clock.systemUTC(), 7);
    }

    public DkimLifecycleService(DkimKeyRepository repository, Clock clock, int overlapDays) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.overlapDays = overlapDays;
    }

    /**
     * Transitions a key to a target lifecycle state.
     */
    public DkimKey transition(long keyId, DkimKeyStatus targetStatus, String triggeredBy, String notes) {
        if (targetStatus == DkimKeyStatus.ACTIVE) {
            return activate(keyId, triggeredBy, notes);
        }

        DkimKey key = findOrThrow(keyId);
        DkimKeyStatus current = key.getStatus();
        validateTransition(current, targetStatus);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (targetStatus == DkimKeyStatus.ROTATING_OUT) {
            if (key.getRetireAfter() == null || key.getRetireAfter().isBefore(now)) {
                key.setRetireAfter(now.plusDays(overlapDays));
            }
        } else if (targetStatus == DkimKeyStatus.RETIRED) {
            if (key.getRetireAfter() != null && key.getRetireAfter().isAfter(now)) {
                throw new IllegalStateException("Key is not eligible for retirement yet");
            }
            key.setRetiredAt(now);
        }

        key.setStatus(targetStatus);
        repository.save(key);
        logEvent(key.getId(), eventTypeFor(targetStatus), current, targetStatus, notes, triggeredBy);
        return key;
    }

    /**
     * Activates a key and demotes existing active key for the domain, if any.
     */
    public DkimKey activate(long keyId, String triggeredBy, String notes) {
        DkimKey key = findOrThrow(keyId);
        DkimKeyStatus current = key.getStatus();
        validateTransition(current, DkimKeyStatus.ACTIVE);

        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<DkimKey> existingActive = repository.findActiveForDomain(key.getDomain());
        if (existingActive.isPresent() && !existingActive.get().getId().equals(key.getId())) {
            DkimKey previous = existingActive.get();
            DkimKeyStatus oldPrevious = previous.getStatus();
            previous.setStatus(DkimKeyStatus.ROTATING_OUT);
            previous.setRetireAfter(now.plusDays(overlapDays));
            repository.save(previous);
            logEvent(previous.getId(), "ROTATING_OUT", oldPrevious, DkimKeyStatus.ROTATING_OUT,
                    "Superseded by key " + key.getId(), triggeredBy);
        }

        key.setStatus(DkimKeyStatus.ACTIVE);
        key.setTestMode(false);
        key.setActivatedAt(now);
        repository.save(key);
        logEvent(key.getId(), "ACTIVATED", current, DkimKeyStatus.ACTIVE, notes, triggeredBy);
        return key;
    }

    private DkimKey findOrThrow(long keyId) {
        return repository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("DKIM key not found: " + keyId));
    }

    private static void validateTransition(DkimKeyStatus current, DkimKeyStatus target) {
        if (current == null) {
            throw new IllegalStateException("Current DKIM key state is missing");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target DKIM key state is required");
        }
        if (current == target) {
            throw new IllegalStateException("Key is already in state " + target);
        }
        if (target == DkimKeyStatus.REVOKED) {
            return;
        }

        boolean allowed = switch (current) {
            case PENDING_PUBLISH -> target == DkimKeyStatus.ACTIVE;
            case ACTIVE -> target == DkimKeyStatus.ROTATING_OUT;
            case ROTATING_OUT -> target == DkimKeyStatus.RETIRED;
            case RETIRED, REVOKED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid DKIM key transition: " + current + " -> " + target);
        }
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

    private static String eventTypeFor(DkimKeyStatus status) {
        return switch (status) {
            case ROTATING_OUT -> "ROTATING_OUT";
            case RETIRED -> "RETIRED";
            case REVOKED -> "REVOKED";
            case ACTIVE -> "ACTIVATED";
            case PENDING_PUBLISH -> "PENDING_PUBLISH";
        };
    }
}
