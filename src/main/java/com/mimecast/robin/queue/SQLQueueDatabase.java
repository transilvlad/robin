package com.mimecast.robin.queue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * SQL-backed scheduled work queue.
 *
 * @param <T> payload type
 */
public abstract class SQLQueueDatabase<T extends Serializable> implements QueueDatabase<T> {
    private static final Logger log = LogManager.getLogger(SQLQueueDatabase.class);

    protected final String tableName;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private HikariDataSource dataSource;

    protected SQLQueueDatabase(DBConfig config) {
        this.jdbcUrl = config.jdbcUrl;
        this.username = config.username;
        this.password = config.password;
        this.tableName = validateTableName(config.tableName);
    }

    protected abstract String getDatabaseType();

    protected abstract String getCreateTableSQL();

    protected String getSelectForClaimSQL() {
        return "SELECT queue_uid FROM " + tableName
                + " WHERE state = ? AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at, created_epoch LIMIT ? FOR UPDATE SKIP LOCKED";
    }

    protected List<String> getCreateIndexSQL() {
        return List.of(
                "CREATE INDEX IF NOT EXISTS " + tableName + "_state_attempt_idx ON " + tableName + " (state, next_attempt_at, created_epoch)",
                "CREATE INDEX IF NOT EXISTS " + tableName + "_claim_idx ON " + tableName + " (state, claimed_until)",
                "CREATE INDEX IF NOT EXISTS " + tableName + "_created_idx ON " + tableName + " (created_epoch)"
        );
    }

    @Override
    public void initialize() {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(1);
            config.setPoolName("robin-queue-" + getDatabaseType());
            this.dataSource = new HikariDataSource(config);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(getCreateTableSQL());
                for (String sql : getCreateIndexSQL()) {
                    try {
                        statement.execute(sql);
                    } catch (SQLException ignored) {
                        log.debug("Skipping queue index statement for {}: {}", getDatabaseType(), sql);
                    }
                }
            }
            log.info("{} queue database initialized: table={}", getDatabaseType(), tableName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize " + getDatabaseType() + " queue database", e);
        }
    }

    @Override
    public QueueItem<T> enqueue(QueueItem<T> item) {
        String sql = "INSERT INTO " + tableName + " (queue_uid, state, next_attempt_at, claimed_until, claim_owner,"
                + " created_epoch, updated_epoch, retry_count, protocol, session_uid, last_error, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindItem(statement, item);
            statement.executeUpdate();
            return item;
        } catch (SQLException e) {
            log.error("Failed to enqueue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    @Override
    public List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds) {
        if (limit <= 0) {
            return List.of();
        }

        String updateSql = "UPDATE " + tableName
                + " SET state = ?, claim_owner = ?, claimed_until = ?, updated_epoch = ?"
                + " WHERE queue_uid = ?";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            List<String> claimedUids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(getSelectForClaimSQL())) {
                select.setString(1, QueueItemState.READY.name());
                select.setLong(2, nowEpochSeconds);
                select.setInt(3, limit);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        claimedUids.add(rs.getString(1));
                    }
                }
            }

            if (claimedUids.isEmpty()) {
                connection.commit();
                return List.of();
            }

            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                for (String uid : claimedUids) {
                    update.setString(1, QueueItemState.CLAIMED.name());
                    update.setString(2, consumerId);
                    update.setLong(3, claimUntilEpochSeconds);
                    update.setLong(4, nowEpochSeconds);
                    update.setString(5, uid);
                    update.addBatch();
                }
                update.executeBatch();
            }

            List<QueueItem<T>> items = fetchByUIDs(connection, claimedUids);
            connection.commit();
            for (QueueItem<T> item : items) {
                item.claim(consumerId, claimUntilEpochSeconds);
            }
            return items;
        } catch (SQLException e) {
            log.error("Failed to claim ready items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to claim ready items", e);
        }
    }

    @Override
    public boolean acknowledge(String uid) {
        return deleteByUID(uid);
    }

    @Override
    public boolean reschedule(QueueItem<T> item, long nextAttemptAtEpochSeconds, String lastError) {
        String sql = "UPDATE " + tableName + " SET state = ?, next_attempt_at = ?, claimed_until = 0,"
                + " claim_owner = NULL, updated_epoch = ?, retry_count = ?, protocol = ?, session_uid = ?,"
                + " last_error = ?, data = ? WHERE queue_uid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            item.readyAt(nextAttemptAtEpochSeconds)
                    .setLastError(lastError)
                    .syncFromPayload();
            statement.setString(1, QueueItemState.READY.name());
            statement.setLong(2, nextAttemptAtEpochSeconds);
            statement.setLong(3, item.getUpdatedAtEpochSeconds());
            statement.setInt(4, item.getRetryCount());
            statement.setString(5, item.getProtocol());
            statement.setString(6, item.getSessionUid());
            setNullableString(statement, 7, lastError);
            statement.setBytes(8, QueuePayloadCodec.serialize(item.getPayload()));
            statement.setString(9, item.getUid());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to reschedule item {}: {}", item.getUid(), e.getMessage(), e);
            throw new RuntimeException("Failed to reschedule item", e);
        }
    }

    @Override
    public int releaseExpiredClaims(long nowEpochSeconds) {
        String sql = "UPDATE " + tableName
                + " SET state = ?, claim_owner = NULL, claimed_until = 0, next_attempt_at = ?, updated_epoch = ?"
                + " WHERE state = ? AND claimed_until <= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, QueueItemState.READY.name());
            statement.setLong(2, nowEpochSeconds);
            statement.setLong(3, nowEpochSeconds);
            statement.setString(4, QueueItemState.CLAIMED.name());
            statement.setLong(5, nowEpochSeconds);
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to release expired claims: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to release expired claims", e);
        }
    }

    @Override
    public boolean markDead(String uid, String lastError) {
        String sql = "UPDATE " + tableName
                + " SET state = ?, claim_owner = NULL, claimed_until = 0, updated_epoch = ?, last_error = ?"
                + " WHERE queue_uid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, QueueItemState.DEAD.name());
            statement.setLong(2, System.currentTimeMillis() / 1000L);
            setNullableString(statement, 3, lastError);
            statement.setString(4, uid);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to mark dead item {}: {}", uid, e.getMessage(), e);
            throw new RuntimeException("Failed to mark dead item", e);
        }
    }

    @Override
    public long size() {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE state IN (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, QueueItemState.READY.name());
            statement.setString(2, QueueItemState.CLAIMED.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to get queue size: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get queue size", e);
        }
    }

    @Override
    public QueueStats stats() {
        long ready = countByState(QueueItemState.READY);
        long claimed = countByState(QueueItemState.CLAIMED);
        long dead = countByState(QueueItemState.DEAD);
        long oldestReady = minEpochForState("next_attempt_at", QueueItemState.READY);
        long oldestClaimed = minEpochForState("claimed_until", QueueItemState.CLAIMED);
        return new QueueStats(ready, claimed, dead, ready + claimed, oldestReady, oldestClaimed);
    }

    @Override
    public QueuePage<T> list(int offset, int limit, QueueListFilter filter) {
        QueueListFilter effectiveFilter = filter != null ? filter : QueueListFilter.activeOnly();
        String where = buildWhereClause(effectiveFilter);
        String countSql = "SELECT COUNT(*) FROM " + tableName + where;
        String sql = "SELECT * FROM " + tableName + where + " ORDER BY created_epoch, queue_uid LIMIT ? OFFSET ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement count = connection.prepareStatement(countSql);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int nextIndex = bindFilter(count, effectiveFilter, 1);
            long total;
            try (ResultSet rs = count.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0L;
            }

            nextIndex = bindFilter(statement, effectiveFilter, 1);
            statement.setInt(nextIndex++, Math.max(0, limit));
            statement.setInt(nextIndex, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                List<QueueItem<T>> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(readItem(rs));
                }
                return new QueuePage<>(total, items);
            }
        } catch (SQLException e) {
            log.error("Failed to list queue items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to list queue items", e);
        }
    }

    @Override
    public QueueItem<T> getByUID(String uid) {
        String sql = "SELECT * FROM " + tableName + " WHERE queue_uid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? readItem(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to fetch queue item {}: {}", uid, e.getMessage(), e);
            throw new RuntimeException("Failed to fetch queue item", e);
        }
    }

    @Override
    public boolean deleteByUID(String uid) {
        String sql = "DELETE FROM " + tableName + " WHERE queue_uid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete queue item {}: {}", uid, e.getMessage(), e);
            throw new RuntimeException("Failed to delete queue item", e);
        }
    }

    @Override
    public int deleteByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }

        List<String> deduped = new ArrayList<>(new LinkedHashSet<>(uids));
        String placeholders = String.join(",", java.util.Collections.nCopies(deduped.size(), "?"));
        String sql = "DELETE FROM " + tableName + " WHERE queue_uid IN (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < deduped.size(); i++) {
                statement.setString(i + 1, deduped.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete queue items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete queue items", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            log.error("Failed to clear queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear queue", e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception e) {
                log.warn("Error closing {} queue datasource: {}", getDatabaseType(), e.getMessage());
            }
        }
    }

    private void bindItem(PreparedStatement statement, QueueItem<T> item) throws SQLException {
        statement.setString(1, item.getUid());
        statement.setString(2, item.getState().name());
        statement.setLong(3, item.getNextAttemptAtEpochSeconds());
        statement.setLong(4, item.getClaimedUntilEpochSeconds());
        setNullableString(statement, 5, item.getClaimOwner());
        statement.setLong(6, item.getCreatedAtEpochSeconds());
        statement.setLong(7, item.getUpdatedAtEpochSeconds());
        statement.setInt(8, item.getRetryCount());
        setNullableString(statement, 9, item.getProtocol());
        setNullableString(statement, 10, item.getSessionUid());
        setNullableString(statement, 11, item.getLastError());
        statement.setBytes(12, QueuePayloadCodec.serialize(item.getPayload()));
    }

    private QueueItem<T> readItem(ResultSet rs) throws SQLException {
        QueueItem<T> item = QueueItem.restore(
                rs.getString("queue_uid"),
                rs.getLong("created_epoch"),
                QueuePayloadCodec.deserialize(rs.getBytes("data"))
        );
        item.setState(QueueItemState.valueOf(rs.getString("state")));
        item.setNextAttemptAtEpochSeconds(rs.getLong("next_attempt_at"));
        item.setClaimedUntilEpochSeconds(rs.getLong("claimed_until"));
        item.setClaimOwner(rs.getString("claim_owner"));
        item.setUpdatedAtEpochSeconds(rs.getLong("updated_epoch"));
        item.setRetryCount(rs.getInt("retry_count"));
        item.setProtocol(rs.getString("protocol"));
        item.setSessionUid(rs.getString("session_uid"));
        item.setLastError(rs.getString("last_error"));
        item.syncFromPayload();
        return item;
    }

    private List<QueueItem<T>> fetchByUIDs(Connection connection, List<String> uids) throws SQLException {
        if (uids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(uids.size(), "?"));
        String sql = "SELECT * FROM " + tableName + " WHERE queue_uid IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < uids.size(); i++) {
                statement.setString(i + 1, uids.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<String, QueueItem<T>> items = new LinkedHashMap<>();
                while (rs.next()) {
                    QueueItem<T> item = readItem(rs);
                    items.put(item.getUid(), item);
                }
                List<QueueItem<T>> ordered = new ArrayList<>(uids.size());
                for (String uid : uids) {
                    QueueItem<T> item = items.get(uid);
                    if (item != null) {
                        ordered.add(item);
                    }
                }
                return ordered;
            }
        }
    }

    private long countByState(QueueItemState state) {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE state = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to count queue state {}: {}", state, e.getMessage(), e);
            throw new RuntimeException("Failed to count queue state", e);
        }
    }

    private long minEpochForState(String column, QueueItemState state) {
        String sql = "SELECT MIN(" + column + ") FROM " + tableName + " WHERE state = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to query min epoch for queue state {}: {}", state, e.getMessage(), e);
            throw new RuntimeException("Failed to query queue stats", e);
        }
    }

    private String buildWhereClause(QueueListFilter filter) {
        List<String> conditions = new ArrayList<>();
        if (filter.getStates() != null && !filter.getStates().isEmpty()) {
            conditions.add("state IN (" + String.join(",", java.util.Collections.nCopies(filter.getStates().size(), "?")) + ")");
        }
        if (filter.getProtocol() != null) {
            conditions.add("LOWER(protocol) = LOWER(?)");
        }
        if (filter.getMinRetryCount() != null) {
            conditions.add("retry_count >= ?");
        }
        if (filter.getMaxRetryCount() != null) {
            conditions.add("retry_count <= ?");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private int bindFilter(PreparedStatement statement, QueueListFilter filter, int startIndex) throws SQLException {
        int index = startIndex;
        if (filter.getStates() != null && !filter.getStates().isEmpty()) {
            for (QueueItemState state : filter.getStates()) {
                statement.setString(index++, state.name());
            }
        }
        if (filter.getProtocol() != null) {
            statement.setString(index++, filter.getProtocol());
        }
        if (filter.getMinRetryCount() != null) {
            statement.setInt(index++, filter.getMinRetryCount());
        }
        if (filter.getMaxRetryCount() != null) {
            statement.setInt(index++, filter.getMaxRetryCount());
        }
        return index;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private String validateTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Table name contains invalid characters: " + tableName);
        }
        return tableName;
    }

    protected static class DBConfig {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String tableName;

        protected DBConfig(String jdbcUrl, String username, String password, String tableName) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.tableName = tableName;
        }
    }
}
