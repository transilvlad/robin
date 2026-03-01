package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimStrategy;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class DkimRotationServiceTest {

    private static HikariDataSource ds;
    private static DkimKeyRepository repository;
    private static Clock fixedClock;

    private DkimRotationService service;

    @BeforeAll
    static void setupDatabase() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:dkim_rotation_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        ds = new HikariDataSource(cfg);

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_keys (
                        id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        domain                VARCHAR(253) NOT NULL,
                        selector              VARCHAR(63)  NOT NULL,
                        algorithm             VARCHAR(10)  NOT NULL,
                        private_key_enc       TEXT         NOT NULL,
                        public_key            TEXT         NOT NULL,
                        dns_record_value      TEXT         NOT NULL,
                        status                VARCHAR(20)  NOT NULL,
                        test_mode             BOOLEAN      DEFAULT TRUE,
                        strategy              VARCHAR(20),
                        service_tag           VARCHAR(63),
                        paired_key_id         BIGINT,
                        rotation_scheduled_at TIMESTAMP WITH TIME ZONE,
                        published_at          TIMESTAMP WITH TIME ZONE,
                        activated_at          TIMESTAMP WITH TIME ZONE,
                        retire_after          TIMESTAMP WITH TIME ZONE,
                        retired_at            TIMESTAMP WITH TIME ZONE,
                        created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uq_dkim_keys_domain_selector UNIQUE (domain, selector)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_rotation_events (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        key_id       BIGINT,
                        event_type   VARCHAR(30) NOT NULL,
                        old_status   VARCHAR(20),
                        new_status   VARCHAR(20),
                        notes        TEXT,
                        triggered_by VARCHAR(50),
                        created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        repository = new DkimKeyRepository(ds);
        fixedClock = Clock.fixed(Instant.parse("2026-02-27T00:00:00Z"), ZoneOffset.UTC);
    }

    @AfterAll
    static void closeDatabase() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void init() throws Exception {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM dkim_rotation_events");
            stmt.execute("DELETE FROM dkim_keys");
        }
        DkimLifecycleService lifecycle = new DkimLifecycleService(repository, fixedClock, 7);
        service = new DkimRotationService(repository, lifecycle, fixedClock);
    }

    @Test
    void confirmPublishedSetsTimestampsForAutoStrategyAndLogsEvent() {
        DkimKey key = repository.save(buildKey("auto1.com", "sel1", DkimKeyStatus.PENDING_PUBLISH, DkimStrategy.AUTO));

        DkimKey published = service.confirmPublished(key.getId(), "USER", "dns published", 3);

        assertEquals(OffsetDateTime.now(fixedClock), published.getPublishedAt());
        assertEquals(OffsetDateTime.now(fixedClock).plusDays(3), published.getRotationScheduledAt());
        assertTrue(eventTypes().contains("PUBLISHED"));
    }

    @Test
    void cutoverAndCleanupFlowTransitionsAndLogs() {
        DkimKey oldActive = repository.save(buildKey("flow.com", "old", DkimKeyStatus.ACTIVE, DkimStrategy.AUTO));
        DkimKey newPending = repository.save(buildKey("flow.com", "new", DkimKeyStatus.PENDING_PUBLISH, DkimStrategy.AUTO));

        DkimKey activeNow = service.cutover(newPending.getId(), "USER", "activate new");
        DkimKey oldNow = repository.findById(oldActive.getId()).orElseThrow();

        assertEquals(DkimKeyStatus.ACTIVE, activeNow.getStatus());
        assertEquals(DkimKeyStatus.ROTATING_OUT, oldNow.getStatus());
        assertEquals(1, activeCount("flow.com"));

        oldNow.setRetireAfter(OffsetDateTime.now(fixedClock).minusMinutes(1));
        repository.save(oldNow);
        DkimKey retired = service.cleanup(oldNow.getId(), "USER", "cleanup old");
        assertEquals(DkimKeyStatus.RETIRED, retired.getStatus());

        List<String> events = eventTypes();
        assertTrue(events.contains("ACTIVATED"));
        assertTrue(events.contains("ROTATING_OUT"));
        assertTrue(events.contains("RETIRED"));
    }

    @Test
    void autoRotationTickProcessesDueCutoverAndCleanup() {
        DkimKey duePending = buildKey("tick.com", "pend", DkimKeyStatus.PENDING_PUBLISH, DkimStrategy.AUTO);
        duePending.setPublishedAt(OffsetDateTime.now(fixedClock).minusDays(2));
        duePending.setRotationScheduledAt(OffsetDateTime.now(fixedClock).minusHours(1));
        duePending = repository.save(duePending);

        DkimKey dueCleanup = buildKey("tick.com", "old", DkimKeyStatus.ROTATING_OUT, DkimStrategy.AUTO);
        dueCleanup.setRetireAfter(OffsetDateTime.now(fixedClock).minusHours(1));
        dueCleanup = repository.save(dueCleanup);

        service.runAutoRotationTick(List.of("tick.com"));

        DkimKey pendingNow = repository.findById(duePending.getId()).orElseThrow();
        DkimKey cleanupNow = repository.findById(dueCleanup.getId()).orElseThrow();

        assertEquals(DkimKeyStatus.ACTIVE, pendingNow.getStatus());
        assertEquals(DkimKeyStatus.RETIRED, cleanupNow.getStatus());
        assertTrue(schedulerTriggeredCount() >= 2);
    }

    @Test
    void schedulerStartAndStopWork() {
        service.startAutoRotationScheduler(List::of, 0, 60);
        assertTrue(service.isAutoRotationSchedulerRunning());

        service.stopAutoRotationScheduler();
        assertFalse(service.isAutoRotationSchedulerRunning());
    }

    private static DkimKey buildKey(String domain, String selector, DkimKeyStatus status, DkimStrategy strategy) {
        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm("RSA_2048");
        key.setPrivateKeyEnc("enc");
        key.setPublicKey("pub");
        key.setDnsRecordValue("v=DKIM1; k=rsa; p=abc");
        key.setStatus(status);
        key.setTestMode(status != DkimKeyStatus.ACTIVE);
        key.setStrategy(strategy);
        return key;
    }

    private int activeCount(String domain) {
        return (int) repository.findByDomain(domain).stream()
                .filter(k -> k.getStatus() == DkimKeyStatus.ACTIVE)
                .count();
    }

    private List<String> eventTypes() {
        List<String> events = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT event_type FROM dkim_rotation_events ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read rotation events", e);
        }
        return events;
    }

    private int schedulerTriggeredCount() {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM dkim_rotation_events WHERE triggered_by = 'SCHEDULER'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read scheduler-triggered events", e);
        }
    }
}
