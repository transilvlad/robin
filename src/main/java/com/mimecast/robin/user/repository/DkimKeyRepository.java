package com.mimecast.robin.user.repository;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimRotationEvent;
import com.mimecast.robin.user.domain.DkimStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC DAO for {@code dkim_keys} and {@code dkim_rotation_events}.
 */
public class DkimKeyRepository {

    private static final Logger log = LogManager.getLogger(DkimKeyRepository.class);

    private static final String INSERT_KEY =
            "INSERT INTO dkim_keys " +
            "(domain, selector, algorithm, private_key_enc, public_key, dns_record_value, " +
            " status, test_mode, strategy, service_tag, paired_key_id, " +
            " rotation_scheduled_at, published_at, activated_at, retire_after, retired_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_KEY =
            "UPDATE dkim_keys SET " +
            "status = ?, test_mode = ?, strategy = ?, service_tag = ?, paired_key_id = ?, " +
            "rotation_scheduled_at = ?, published_at = ?, activated_at = ?, retire_after = ?, retired_at = ? " +
            "WHERE id = ?";

    private static final String SELECT_BY_ID =
            "SELECT * FROM dkim_keys WHERE id = ?";

    private static final String SELECT_BY_DOMAIN =
            "SELECT * FROM dkim_keys WHERE domain = ? ORDER BY created_at DESC";

    private static final String SELECT_ACTIVE_FOR_DOMAIN =
            "SELECT * FROM dkim_keys WHERE domain = ? AND status = 'ACTIVE' LIMIT 1";

    private static final String UPDATE_STATUS =
            "UPDATE dkim_keys SET status = ? WHERE id = ?";

    private static final String INSERT_EVENT =
            "INSERT INTO dkim_rotation_events " +
            "(key_id, event_type, old_status, new_status, notes, triggered_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPSERT_DETECTED =
            "INSERT INTO dkim_detected_selectors " +
            "(domain, selector, public_key_dns, algorithm, test_mode, revoked, detected_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (domain, selector) DO UPDATE SET " +
            " public_key_dns = EXCLUDED.public_key_dns, " +
            " algorithm = EXCLUDED.algorithm, " +
            " test_mode = EXCLUDED.test_mode, " +
            " revoked = EXCLUDED.revoked, " +
            " detected_at = NOW()";

    private static final String SELECT_DETECTED_BY_DOMAIN =
            "SELECT * FROM dkim_detected_selectors WHERE domain = ? ORDER BY selector ASC";

    private final DataSource dataSource;

    public DkimKeyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Inserts a new key (when {@code id} is null) or updates mutable fields of an existing one.
     *
     * @param key key to persist
     * @return the same key instance, with {@code id} populated after insert
     */
    public DkimKey save(DkimKey key) {
        if (key.getId() == null) {
            return insert(key);
        }
        update(key);
        return key;
    }

    /**
     * Returns a key by primary key.
     */
    public Optional<DkimKey> findById(long id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapKey(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("findById({}) failed: {}", id, e.getMessage());
            throw new IllegalStateException("Failed to find DKIM key by id", e);
        }
    }

    /**
     * Returns all keys for a domain, newest first.
     */
    public List<DkimKey> findByDomain(String domain) {
        List<DkimKey> keys = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_DOMAIN)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(mapKey(rs));
                }
            }
        } catch (SQLException e) {
            log.error("findByDomain({}) failed: {}", domain, e.getMessage());
            throw new IllegalStateException("Failed to find DKIM keys by domain", e);
        }
        return keys;
    }

    /**
     * Returns the single ACTIVE key for a domain, if one exists.
     */
    public Optional<DkimKey> findActiveForDomain(String domain) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_FOR_DOMAIN)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapKey(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("findActiveForDomain({}) failed: {}", domain, e.getMessage());
            throw new IllegalStateException("Failed to find active DKIM key", e);
        }
    }

    /**
     * Updates only the {@code status} column of an existing key.
     */
    public void updateStatus(long id, DkimKeyStatus newStatus) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_STATUS)) {
            ps.setString(1, newStatus.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("updateStatus({}, {}) failed: {}", id, newStatus, e.getMessage());
            throw new IllegalStateException("Failed to update DKIM key status", e);
        }
    }

    /**
     * Inserts a rotation audit event. Populates {@code event.id} after insert.
     */
    public void logEvent(DkimRotationEvent event) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_EVENT, Statement.RETURN_GENERATED_KEYS)) {
            if (event.getKeyId() != null) {
                ps.setLong(1, event.getKeyId());
            } else {
                ps.setNull(1, Types.BIGINT);
            }
            ps.setString(2, event.getEventType());
            ps.setString(3, event.getOldStatus());
            ps.setString(4, event.getNewStatus());
            ps.setString(5, event.getNotes());
            ps.setString(6, event.getTriggeredBy());
            ps.executeUpdate();

            try (ResultSet generated = ps.getGeneratedKeys()) {
                if (generated.next()) {
                    event.setId(generated.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.error("logEvent(keyId={}) failed: {}", event.getKeyId(), e.getMessage());
            throw new IllegalStateException("Failed to log DKIM rotation event", e);
        }
    }

    /**
     * Upserts a detected selector into {@code dkim_detected_selectors}.
     *
     * @param selector detected selector
     */
    public void saveDetectedSelector(com.mimecast.robin.user.domain.DkimDetectedSelector selector) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_DETECTED)) {
            ps.setString(1, selector.getDomain());
            ps.setString(2, selector.getSelector());
            ps.setString(3, selector.getPublicKeyDns());
            ps.setString(4, selector.getAlgorithm());
            if (selector.getTestMode() != null) {
                ps.setBoolean(5, selector.getTestMode());
            } else {
                ps.setNull(5, Types.BOOLEAN);
            }
            ps.setBoolean(6, selector.isRevoked());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("saveDetectedSelector(domain={}, selector={}) failed: {}", selector.getDomain(), selector.getSelector(), e.getMessage());
            throw new IllegalStateException("Failed to save detected DKIM selector", e);
        }
    }

    /**
     * Returns all detected selectors for a domain.
     */
    public List<com.mimecast.robin.user.domain.DkimDetectedSelector> findDetectedSelectorsByDomain(String domain) {
        List<com.mimecast.robin.user.domain.DkimDetectedSelector> selectors = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_DETECTED_BY_DOMAIN)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    selectors.add(mapDetectedSelector(rs));
                }
            }
        } catch (SQLException e) {
            log.error("findDetectedSelectorsByDomain({}) failed: {}", domain, e.getMessage());
            throw new IllegalStateException("Failed to find detected DKIM selectors", e);
        }
        return selectors;
    }

    // --- private helpers ---

    private DkimKey insert(DkimKey key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_KEY, Statement.RETURN_GENERATED_KEYS)) {
            bindKeyParams(ps, key);
            ps.executeUpdate();

            try (ResultSet generated = ps.getGeneratedKeys()) {
                if (generated.next()) {
                    key.setId(generated.getLong(1));
                }
            }
            return key;
        } catch (SQLException e) {
            log.error("insert DkimKey(domain={}, selector={}) failed: {}", key.getDomain(), key.getSelector(), e.getMessage());
            throw new IllegalStateException("Failed to insert DKIM key", e);
        }
    }

    private void update(DkimKey key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_KEY)) {
            ps.setString(1, key.getStatus() != null ? key.getStatus().name() : null);
            ps.setBoolean(2, key.isTestMode());
            ps.setString(3, key.getStrategy() != null ? key.getStrategy().name() : null);
            ps.setString(4, key.getServiceTag());
            if (key.getPairedKeyId() != null) {
                ps.setLong(5, key.getPairedKeyId());
            } else {
                ps.setNull(5, Types.BIGINT);
            }
            setTimestamp(ps, 6, key.getRotationScheduledAt());
            setTimestamp(ps, 7, key.getPublishedAt());
            setTimestamp(ps, 8, key.getActivatedAt());
            setTimestamp(ps, 9, key.getRetireAfter());
            setTimestamp(ps, 10, key.getRetiredAt());
            ps.setLong(11, key.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("update DkimKey(id={}) failed: {}", key.getId(), e.getMessage());
            throw new IllegalStateException("Failed to update DKIM key", e);
        }
    }

    private void bindKeyParams(PreparedStatement ps, DkimKey key) throws SQLException {
        ps.setString(1, key.getDomain());
        ps.setString(2, key.getSelector());
        ps.setString(3, key.getAlgorithm());
        ps.setString(4, key.getPrivateKeyEnc());
        ps.setString(5, key.getPublicKey());
        ps.setString(6, key.getDnsRecordValue());
        ps.setString(7, key.getStatus() != null ? key.getStatus().name() : null);
        ps.setBoolean(8, key.isTestMode());
        ps.setString(9, key.getStrategy() != null ? key.getStrategy().name() : null);
        ps.setString(10, key.getServiceTag());
        if (key.getPairedKeyId() != null) {
            ps.setLong(11, key.getPairedKeyId());
        } else {
            ps.setNull(11, Types.BIGINT);
        }
        setTimestamp(ps, 12, key.getRotationScheduledAt());
        setTimestamp(ps, 13, key.getPublishedAt());
        setTimestamp(ps, 14, key.getActivatedAt());
        setTimestamp(ps, 15, key.getRetireAfter());
        setTimestamp(ps, 16, key.getRetiredAt());
    }

    private DkimKey mapKey(ResultSet rs) throws SQLException {
        DkimKey key = new DkimKey();
        key.setId(rs.getLong("id"));
        key.setDomain(rs.getString("domain"));
        key.setSelector(rs.getString("selector"));
        key.setAlgorithm(rs.getString("algorithm"));
        key.setPrivateKeyEnc(rs.getString("private_key_enc"));
        key.setPublicKey(rs.getString("public_key"));
        key.setDnsRecordValue(rs.getString("dns_record_value"));
        key.setStatus(DkimKeyStatus.valueOf(rs.getString("status")));
        key.setTestMode(rs.getBoolean("test_mode"));

        String strategy = rs.getString("strategy");
        key.setStrategy(strategy != null ? DkimStrategy.valueOf(strategy) : null);
        key.setServiceTag(rs.getString("service_tag"));

        long pairedId = rs.getLong("paired_key_id");
        key.setPairedKeyId(rs.wasNull() ? null : pairedId);

        key.setRotationScheduledAt(toOffsetDateTime(rs.getTimestamp("rotation_scheduled_at")));
        key.setPublishedAt(toOffsetDateTime(rs.getTimestamp("published_at")));
        key.setActivatedAt(toOffsetDateTime(rs.getTimestamp("activated_at")));
        key.setRetireAfter(toOffsetDateTime(rs.getTimestamp("retire_after")));
        key.setRetiredAt(toOffsetDateTime(rs.getTimestamp("retired_at")));
        key.setCreatedAt(toOffsetDateTime(rs.getTimestamp("created_at")));
        return key;
    }

    private void setTimestamp(PreparedStatement ps, int idx, OffsetDateTime dt) throws SQLException {
        if (dt == null) {
            ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(idx, dt);
        }
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private com.mimecast.robin.user.domain.DkimDetectedSelector mapDetectedSelector(ResultSet rs) throws SQLException {
        com.mimecast.robin.user.domain.DkimDetectedSelector selector = new com.mimecast.robin.user.domain.DkimDetectedSelector();
        selector.setId(rs.getLong("id"));
        selector.setDomain(rs.getString("domain"));
        selector.setSelector(rs.getString("selector"));
        selector.setPublicKeyDns(rs.getString("public_key_dns"));
        selector.setAlgorithm(rs.getString("algorithm"));
        boolean testMode = rs.getBoolean("test_mode");
        selector.setTestMode(rs.wasNull() ? null : testMode);
        selector.setRevoked(rs.getBoolean("revoked"));
        selector.setDetectedAt(rs.getTimestamp("detected_at").toInstant());
        return selector;
    }
}
