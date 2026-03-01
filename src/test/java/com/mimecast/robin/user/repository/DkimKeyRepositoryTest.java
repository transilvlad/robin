package com.mimecast.robin.user.repository;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimRotationEvent;
import com.mimecast.robin.user.domain.DkimStrategy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimKeyRepositoryTest {

    private static HikariDataSource ds;
    private static DkimKeyRepository repo;

    @BeforeAll
    static void setupDatabase() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:dkim_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
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

        repo = new DkimKeyRepository(ds);
    }

    @AfterAll
    static void closeDatabase() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM dkim_rotation_events");
            stmt.execute("DELETE FROM dkim_keys");
        }
    }

    // --- save (insert) ---

    @Test
    void saveInsertAssignsId() {
        DkimKey saved = repo.save(buildKey("example.com", "20260227"));
        assertNotNull(saved.getId());
        assertTrue(saved.getId() > 0);
    }

    @Test
    void saveInsertRoundTrip() {
        DkimKey key = buildKey("roundtrip.com", "sel-rt");
        key.setStrategy(DkimStrategy.DUAL_ALGO);
        key.setServiceTag("transactional");
        DkimKey saved = repo.save(key);

        Optional<DkimKey> found = repo.findById(saved.getId());
        assertTrue(found.isPresent());
        DkimKey k = found.get();
        assertEquals("roundtrip.com", k.getDomain());
        assertEquals("sel-rt", k.getSelector());
        assertEquals("RSA_2048", k.getAlgorithm());
        assertEquals(DkimKeyStatus.PENDING_PUBLISH, k.getStatus());
        assertTrue(k.isTestMode());
        assertEquals(DkimStrategy.DUAL_ALGO, k.getStrategy());
        assertEquals("transactional", k.getServiceTag());
        assertNotNull(k.getCreatedAt());
    }

    // --- save (update) ---

    @Test
    void saveUpdateMutatesFields() {
        DkimKey key = repo.save(buildKey("update.com", "sel-upd"));
        key.setStatus(DkimKeyStatus.ACTIVE);
        key.setTestMode(false);
        repo.save(key);

        DkimKey found = repo.findById(key.getId()).orElseThrow();
        assertEquals(DkimKeyStatus.ACTIVE, found.getStatus());
        assertFalse(found.isTestMode());
    }

    // --- findById ---

    @Test
    void findByIdFound() {
        DkimKey saved = repo.save(buildKey("find.com", "sel1"));
        Optional<DkimKey> result = repo.findById(saved.getId());
        assertTrue(result.isPresent());
        assertEquals("find.com", result.get().getDomain());
    }

    @Test
    void findByIdNotFound() {
        assertTrue(repo.findById(Long.MAX_VALUE).isEmpty());
    }

    // --- findByDomain ---

    @Test
    void findByDomainReturnsAllKeysForDomain() {
        repo.save(buildKey("multi.com", "s1"));
        repo.save(buildKey("multi.com", "s2"));
        repo.save(buildKey("other.com", "s3"));

        List<DkimKey> keys = repo.findByDomain("multi.com");
        assertEquals(2, keys.size());
        assertTrue(keys.stream().allMatch(k -> "multi.com".equals(k.getDomain())));
    }

    @Test
    void findByDomainEmptyWhenNoneExist() {
        assertTrue(repo.findByDomain("nobody.com").isEmpty());
    }

    // --- findActiveForDomain ---

    @Test
    void findActiveForDomainReturnsActiveKey() {
        DkimKey pending = buildKey("active.com", "sel-pend");
        repo.save(pending);

        DkimKey active = buildKey("active.com", "sel-active");
        active.setStatus(DkimKeyStatus.ACTIVE);
        repo.save(active);

        Optional<DkimKey> result = repo.findActiveForDomain("active.com");
        assertTrue(result.isPresent());
        assertEquals(DkimKeyStatus.ACTIVE, result.get().getStatus());
        assertEquals("sel-active", result.get().getSelector());
    }

    @Test
    void findActiveForDomainEmptyWhenNoActiveKey() {
        repo.save(buildKey("noactive.com", "sel-pend"));
        assertTrue(repo.findActiveForDomain("noactive.com").isEmpty());
    }

    // --- updateStatus ---

    @Test
    void updateStatusChangesStatusColumn() {
        DkimKey key = repo.save(buildKey("status.com", "sel-s"));
        assertEquals(DkimKeyStatus.PENDING_PUBLISH, repo.findById(key.getId()).orElseThrow().getStatus());

        repo.updateStatus(key.getId(), DkimKeyStatus.ACTIVE);
        assertEquals(DkimKeyStatus.ACTIVE, repo.findById(key.getId()).orElseThrow().getStatus());

        repo.updateStatus(key.getId(), DkimKeyStatus.ROTATING_OUT);
        assertEquals(DkimKeyStatus.ROTATING_OUT, repo.findById(key.getId()).orElseThrow().getStatus());

        repo.updateStatus(key.getId(), DkimKeyStatus.RETIRED);
        assertEquals(DkimKeyStatus.RETIRED, repo.findById(key.getId()).orElseThrow().getStatus());

        repo.updateStatus(key.getId(), DkimKeyStatus.REVOKED);
        assertEquals(DkimKeyStatus.REVOKED, repo.findById(key.getId()).orElseThrow().getStatus());
    }

    // --- logEvent ---

    @Test
    void logEventAssignsIdAndPersists() {
        DkimKey key = repo.save(buildKey("events.com", "sel-evt"));

        DkimRotationEvent event = new DkimRotationEvent();
        event.setKeyId(key.getId());
        event.setEventType("ACTIVATED");
        event.setOldStatus("PENDING_PUBLISH");
        event.setNewStatus("ACTIVE");
        event.setNotes("Manual activation by operator");
        event.setTriggeredBy("USER");

        repo.logEvent(event);
        assertNotNull(event.getId());
        assertTrue(event.getId() > 0);
    }

    @Test
    void logEventWithNullOptionalFields() {
        DkimKey key = repo.save(buildKey("events2.com", "sel-evt2"));

        DkimRotationEvent event = new DkimRotationEvent();
        event.setKeyId(key.getId());
        event.setEventType("GENERATED");

        repo.logEvent(event);
        assertNotNull(event.getId());
    }

    // --- helpers ---

    private static DkimKey buildKey(String domain, String selector) {
        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm("RSA_2048");
        key.setPrivateKeyEnc("enc-private-key-placeholder");
        key.setPublicKey("-----BEGIN PUBLIC KEY-----\nMIIB...\n-----END PUBLIC KEY-----\n");
        key.setDnsRecordValue("v=DKIM1; k=rsa; p=MIIBIjAN");
        key.setStatus(DkimKeyStatus.PENDING_PUBLISH);
        key.setTestMode(true);
        key.setStrategy(DkimStrategy.MANUAL);
        return key;
    }
}
