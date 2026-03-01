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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class DkimLifecycleServiceTest {

    private static HikariDataSource ds;
    private static DkimKeyRepository repository;
    private static Clock fixedClock;

    private DkimLifecycleService service;

    @BeforeAll
    static void setupDatabase() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:dkim_lifecycle_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
        service = new DkimLifecycleService(repository, fixedClock, 7);
    }

    @Test
    void pendingToActiveIsAllowedAndLogged() {
        DkimKey key = repository.save(buildKey("example.com", "sel1", DkimKeyStatus.PENDING_PUBLISH));

        DkimKey activated = service.transition(key.getId(), DkimKeyStatus.ACTIVE, "USER", "activate");

        assertEquals(DkimKeyStatus.ACTIVE, activated.getStatus());
        assertTrue(!activated.isTestMode());
        assertEquals(OffsetDateTime.now(fixedClock), activated.getActivatedAt());
        assertTrue(eventTypes().contains("ACTIVATED"));
    }

    @Test
    void invalidTransitionIsRejected() {
        DkimKey key = repository.save(buildKey("example.com", "sel2", DkimKeyStatus.PENDING_PUBLISH));

        assertThrows(IllegalStateException.class,
                () -> service.transition(key.getId(), DkimKeyStatus.RETIRED, "USER", "invalid"));
    }

    @Test
    void rotatingOutCannotRetireBeforeRetireAfter() {
        DkimKey key = buildKey("example.com", "sel3", DkimKeyStatus.ROTATING_OUT);
        key.setRetireAfter(OffsetDateTime.now(fixedClock).plusDays(1));
        key = repository.save(key);
        long keyId = key.getId();

        assertThrows(IllegalStateException.class,
                () -> service.transition(keyId, DkimKeyStatus.RETIRED, "USER", "too early"));
    }

    @Test
    void rotatingOutCanRetireAfterOverlapWindow() {
        DkimKey key = buildKey("example.com", "sel4", DkimKeyStatus.ROTATING_OUT);
        key.setRetireAfter(OffsetDateTime.now(fixedClock).minusDays(1));
        key = repository.save(key);

        DkimKey retired = service.transition(key.getId(), DkimKeyStatus.RETIRED, "USER", "cleanup");

        assertEquals(DkimKeyStatus.RETIRED, retired.getStatus());
        assertNotNull(retired.getRetiredAt());
        assertTrue(eventTypes().contains("RETIRED"));
    }

    @Test
    void activationEnforcesSingleActiveKeyPerDomain() {
        DkimKey oldActive = buildKey("single-active.com", "active-old", DkimKeyStatus.ACTIVE);
        oldActive.setTestMode(false);
        oldActive = repository.save(oldActive);

        DkimKey pending = repository.save(buildKey("single-active.com", "pending-new", DkimKeyStatus.PENDING_PUBLISH));

        DkimKey activated = service.activate(pending.getId(), "USER", "rotate");
        DkimKey refreshedOld = repository.findById(oldActive.getId()).orElseThrow();

        assertEquals(DkimKeyStatus.ACTIVE, activated.getStatus());
        assertEquals(DkimKeyStatus.ROTATING_OUT, refreshedOld.getStatus());
        assertEquals(1, activeCount("single-active.com"));
        assertTrue(eventTypes().contains("ROTATING_OUT"));
        assertTrue(eventTypes().contains("ACTIVATED"));
    }

    @Test
    void revokeIsAllowedFromAnyState() {
        DkimKey key = repository.save(buildKey("revoke.com", "sel5", DkimKeyStatus.ACTIVE));

        DkimKey revoked = service.transition(key.getId(), DkimKeyStatus.REVOKED, "USER", "emergency revoke");

        assertEquals(DkimKeyStatus.REVOKED, revoked.getStatus());
        assertTrue(eventTypes().contains("REVOKED"));
    }

    private static DkimKey buildKey(String domain, String selector, DkimKeyStatus status) {
        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm("RSA_2048");
        key.setPrivateKeyEnc("enc");
        key.setPublicKey("pub");
        key.setDnsRecordValue("v=DKIM1; k=rsa; p=abc");
        key.setStatus(status);
        key.setTestMode(status != DkimKeyStatus.ACTIVE);
        key.setStrategy(DkimStrategy.MANUAL);
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
}
