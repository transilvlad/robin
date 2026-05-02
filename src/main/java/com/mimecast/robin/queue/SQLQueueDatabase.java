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
    private final int maxPoolSize;
    private HikariDataSource dataSource;

    protected SQLQueueDatabase(DBConfig config) {
        this.jdbcUrl = config.jdbcUrl;
        this.username = config.username;
        this.password = config.password;
        this.tableName = validateTableName(config.tableName);
        this.maxPoolSize = config.maxPoolSize;
    }

    protected abstract String getDatabaseType();

    protected abstract String getCreateTableSQL();

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
            config.setMaximumPoolSize(maxPoolSize);
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

                if (!isQueueSchemaCompatible(connection)) {
                    recreateQueueTable(connection);
                }
            }
            log.info("{} queue database initialized: table={}, maxPoolSize={}", getDatabaseType(), tableName, maxPoolSize);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize " + getDatabaseType() + " queue database", e);
        }
    }

    /**
     * Validates that the configured table matches Robin's expected schema.
     *
     * <p>The queue schema has evolved over time. If an older schema is present, queries that
     * reference modern columns (for example, {@code state}) will fail at runtime.
     *
     * @param connection Database connection.
     * @return True if the schema looks compatible; otherwise, false.
     */
    private boolean isQueueSchemaCompatible(Connection connection) {
        String sql = "SELECT queue_uid, state, next_attempt_at, claimed_until, created_epoch, updated_epoch, retry_count, data FROM "
                + tableName + " LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.executeQuery();
            return true;
        } catch (SQLException e) {
            String state = e.getSQLState();
            boolean missingColumn = "42703".equals(state) || "42S22".equals(state);
            if (missingColumn) {
                return false;
            }

            log.warn("Queue schema validation failed for {} (sqlState={}): {}", tableName, state, e.getMessage());
            return true;
        }
    }

    /**
     * Recreates the queue table when an incompatible legacy schema is detected.
     *
     * <p>The existing table is renamed to a legacy name to preserve data for inspection.
     * The new table is then created using the current schema and indexes.
     *
     * @param connection Database connection.
     * @throws SQLException If migration cannot be completed.
     */
    private void recreateQueueTable(Connection connection) throws SQLException {
        String legacyName = tableName + "_legacy_" + (System.currentTimeMillis() / 1000);
        log.warn("Queue table schema mismatch detected for {}. Recreating table; legacy table will be preserved as {}.", tableName, legacyName);

        try (Statement st = connection.createStatement()) {
            try {
                st.execute(getRenameTableSQL(legacyName));
            } catch (SQLException renameError) {
                log.warn("Unable to rename legacy queue table {}: {}. Attempting to drop and recreate.", tableName, renameError.getMessage());
                st.execute("DROP TABLE IF EXISTS " + tableName);
            }

            st.execute(getCreateTableSQL());
            for (String sql : getCreateIndexSQL()) {
                try {
                    st.execute(sql);
                } catch (SQLException ignored) {
                    log.debug("Skipping queue index statement for {}: {}", getDatabaseType(), sql);
                }
            }
        }
    }

    private String getRenameTableSQL(String legacyTableName) {
        if ("PostgreSQL".equals(getDatabaseType())) {
            return "ALTER TABLE " + tableName + " RENAME TO " + legacyTableName;
        }
        return "RENAME TABLE " + tableName + " TO " + legacyTableName;
    }

    @Override
    public QueueItem<T> enqueue(QueueItem<T> item) {
        String sql = insertSql();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindItem(statement, item.readyAt(item.getNextAttemptAtEpochSeconds()).syncFromPayload());
            statement.executeUpdate();
            return item;
        } catch (SQLException e) {
            log.error("Failed to enqueue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    @Override
    public void applyMutations(QueueMutationBatch<T> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            List<String> ackUids = new ArrayList<>();
            List<QueueMutation<T>> reschedules = new ArrayList<>();
            List<QueueMutation<T>> deadItems = new ArrayList<>();
            for (QueueMutation<T> mutation : batch.mutations()) {
                if (mutation == null || mutation.item() == null) {
                    continue;
                }
                switch (mutation.type()) {
                    case ACK -> ackUids.add(mutation.item().getUid());
                    case RESCHEDULE -> reschedules.add(mutation);
                    case DEAD -> deadItems.add(mutation);
                }
            }

            deleteBatch(connection, ackUids);
            updateReschedules(connection, reschedules);
            updateDead(connection, deadItems);
            insertNewItems(connection, batch.newItems());

            connection.commit();
        } catch (Exception e) {
            log.error("Failed to apply queue mutations: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to apply queue mutations", e);
        }
    }

    @Override
    public List<QueueItem<T>> claimReady(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds) {
        if (limit <= 0) {
            return List.of();
        }

        if (!"PostgreSQL".equals(getDatabaseType())) {
            return claimReadyLegacy(limit, nowEpochSeconds, consumerId, claimUntilEpochSeconds);
        }

        String sql = "WITH claimed AS ("
                + " SELECT queue_uid FROM " + tableName
                + " WHERE state = ? AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at, created_epoch LIMIT ? FOR UPDATE SKIP LOCKED"
                + "), updated AS ("
                + " UPDATE " + tableName + " q SET state = ?, claim_owner = ?, claimed_until = ?, updated_epoch = ?"
                + " FROM claimed WHERE q.queue_uid = claimed.queue_uid"
                + " RETURNING q.*"
                + ") SELECT * FROM updated ORDER BY next_attempt_at, created_epoch";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, QueueItemState.READY.name());
            statement.setLong(2, nowEpochSeconds);
            statement.setInt(3, limit);
            statement.setString(4, QueueItemState.CLAIMED.name());
            statement.setString(5, consumerId);
            statement.setLong(6, claimUntilEpochSeconds);
            statement.setLong(7, nowEpochSeconds);
            try (ResultSet rs = statement.executeQuery()) {
                List<QueueItem<T>> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(readItem(rs));
                }
                return items;
            }
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
        boolean exists = getByUID(item.getUid()) != null;
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.reschedule(item, nextAttemptAtEpochSeconds, lastError)), List.of()));
        return exists;
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
        QueueItem<T> item = getByUID(uid);
        if (item == null) {
            return false;
        }
        applyMutations(new QueueMutationBatch<>(List.of(QueueMutation.dead(item, lastError)), List.of()));
        return true;
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

    private List<QueueItem<T>> claimReadyLegacy(int limit, long nowEpochSeconds, String consumerId, long claimUntilEpochSeconds) {
        String selectSql = "SELECT queue_uid FROM " + tableName
                + " WHERE state = ? AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at, created_epoch LIMIT ? FOR UPDATE SKIP LOCKED";
        String updateSql = "UPDATE " + tableName
                + " SET state = ?, claim_owner = ?, claimed_until = ?, updated_epoch = ?"
                + " WHERE queue_uid = ?";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            List<String> claimedUids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
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
            return items;
        } catch (SQLException e) {
            log.error("Failed to claim ready items: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to claim ready items", e);
        }
    }

    private String insertSql() {
        return "INSERT INTO " + tableName + " (queue_uid, state, next_attempt_at, claimed_until, claim_owner,"
                + " created_epoch, updated_epoch, retry_count, protocol, session_uid, last_error, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private void deleteBatch(Connection connection, List<String> ackUids) throws SQLException {
        if (ackUids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ackUids.size(), "?"));
        String sql = "DELETE FROM " + tableName + " WHERE queue_uid IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ackUids.size(); i++) {
                statement.setString(i + 1, ackUids.get(i));
            }
            statement.executeUpdate();
        }
    }

    private void updateReschedules(Connection connection, List<QueueMutation<T>> mutations) throws SQLException {
        if (mutations.isEmpty()) {
            return;
        }
        String sql = "UPDATE " + tableName + " SET state = ?, next_attempt_at = ?, claimed_until = 0,"
                + " claim_owner = NULL, updated_epoch = ?, retry_count = ?, protocol = ?, session_uid = ?,"
                + " last_error = ?, data = ? WHERE queue_uid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (QueueMutation<T> mutation : mutations) {
                QueueItem<T> item = mutation.item().readyAt(mutation.nextAttemptAtEpochSeconds())
                        .setLastError(mutation.lastError());
                statement.setString(1, QueueItemState.READY.name());
                statement.setLong(2, item.getNextAttemptAtEpochSeconds());
                statement.setLong(3, item.getUpdatedAtEpochSeconds());
                statement.setInt(4, item.getRetryCount());
                setNullableString(statement, 5, item.getProtocol());
                setNullableString(statement, 6, item.getSessionUid());
                setNullableString(statement, 7, item.getLastError());
                statement.setBytes(8, QueuePayloadCodec.serialize(item.getPayload()));
                statement.setString(9, item.getUid());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void updateDead(Connection connection, List<QueueMutation<T>> mutations) throws SQLException {
        if (mutations.isEmpty()) {
            return;
        }
        String sql = "UPDATE " + tableName
                + " SET state = ?, claim_owner = NULL, claimed_until = 0, updated_epoch = ?, retry_count = ?,"
                + " protocol = ?, session_uid = ?, last_error = ?, data = ? WHERE queue_uid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (QueueMutation<T> mutation : mutations) {
                QueueItem<T> item = mutation.item().dead(mutation.lastError());
                statement.setString(1, QueueItemState.DEAD.name());
                statement.setLong(2, item.getUpdatedAtEpochSeconds());
                statement.setInt(3, item.getRetryCount());
                setNullableString(statement, 4, item.getProtocol());
                setNullableString(statement, 5, item.getSessionUid());
                setNullableString(statement, 6, item.getLastError());
                statement.setBytes(7, QueuePayloadCodec.serialize(item.getPayload()));
                statement.setString(8, item.getUid());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertNewItems(Connection connection, List<T> newItems) throws SQLException {
        if (newItems.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql())) {
            for (T newItem : newItems) {
                bindItem(statement, QueueItem.ready(newItem));
                statement.addBatch();
            }
            statement.executeBatch();
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

    protected QueueItem<T> readItem(ResultSet rs) throws SQLException {
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
        private final int maxPoolSize;

        protected DBConfig(String jdbcUrl, String username, String password, String tableName, int maxPoolSize) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.tableName = tableName;
            this.maxPoolSize = maxPoolSize;
        }
    }
}
