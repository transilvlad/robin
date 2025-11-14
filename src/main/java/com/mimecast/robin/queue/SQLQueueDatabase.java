package com.mimecast.robin.queue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Abstract base class for SQL-based queue database implementations.
 * <p>Provides common functionality for MariaDB and PostgreSQL backends including:
 * <ul>
 *   <li>Connection management</li>
 *   <li>FIFO queue operations (enqueue, dequeue, peek)</li>
 *   <li>Item removal by index or UID</li>
 *   <li>Serialization/deserialization of queue items</li>
 * </ul>
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public abstract class SQLQueueDatabase<T extends Serializable> implements QueueDatabase<T> {
    private static final Logger log = LogManager.getLogger(SQLQueueDatabase.class);

    protected Connection connection;
    protected final String tableName;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * Constructs a new SQLQueueDatabase instance.
     *
     * @param config Database configuration including connection details
     * @throws IllegalArgumentException if table name contains invalid characters
     */
    protected SQLQueueDatabase(DBConfig config) {
        this.jdbcUrl = config.jdbcUrl;
        this.username = config.username;
        this.password = config.password;
        this.tableName = validateTableName(config.tableName);
    }
    
    /**
     * Validate table name to prevent SQL injection.
     * Only allows alphanumeric characters and underscores.
     *
     * @param tableName Table name to validate
     * @return Validated table name
     * @throws IllegalArgumentException if table name contains invalid characters
     */
    private String validateTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Table name contains invalid characters. Only alphanumeric and underscore allowed: " + tableName);
        }
        return tableName;
    }

    /**
     * Get the database type name for logging.
     *
     * @return Database type (e.g., "MariaDB", "PostgreSQL")
     */
    protected abstract String getDatabaseType();

    /**
     * Get the SQL for creating the queue table.
     * <p>Should include table structure with id, data, and created_at columns.
     *
     * @return CREATE TABLE SQL statement
     */
    protected abstract String getCreateTableSQL();

    /**
     * Initialize the database connection and create table if needed.
     */
    @Override
    public void initialize() {
        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            createTableIfNotExists();
            log.info("{} queue database initialized: table={}", getDatabaseType(), tableName);
        } catch (SQLException e) {
            log.error("Failed to initialize {} queue database: {}", getDatabaseType(), e.getMessage(), e);
            throw new RuntimeException("Failed to initialize " + getDatabaseType() + " queue database", e);
        }
    }

    /**
     * Create the queue table if it doesn't exist.
     *
     * @throws SQLException if table creation fails
     */
    private void createTableIfNotExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(getCreateTableSQL());
            log.debug("Queue table '{}' checked/created", tableName);
        }
    }

    @Override
    public void enqueue(T item) {
        String sql = "INSERT INTO " + tableName + " (data) VALUES (?)";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setBytes(1, serialize(item));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to enqueue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    @Override
    public T dequeue() {
        String selectSQL = "SELECT id, data FROM " + tableName + " ORDER BY id LIMIT 1";
        String deleteSQL = "DELETE FROM " + tableName + " WHERE id = ?";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(selectSQL)) {
            
            if (rs.next()) {
                long id = rs.getLong("id");
                byte[] data = rs.getBytes("data");
                T item = deserialize(data);
                
                try (PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
                    pstmt.setLong(1, id);
                    pstmt.executeUpdate();
                }
                
                return item;
            }
            return null;
        } catch (SQLException e) {
            log.error("Failed to dequeue item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to dequeue item", e);
        }
    }

    @Override
    public T peek() {
        String sql = "SELECT data FROM " + tableName + " ORDER BY id LIMIT 1";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                byte[] data = rs.getBytes("data");
                return deserialize(data);
            }
            return null;
        } catch (SQLException e) {
            log.error("Failed to peek item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to peek item", e);
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public long size() {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            log.error("Failed to get queue size: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get queue size", e);
        }
    }

    @Override
    public List<T> snapshot() {
        String sql = "SELECT data FROM " + tableName + " ORDER BY id";
        List<T> items = new ArrayList<>();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                byte[] data = rs.getBytes("data");
                items.add(deserialize(data));
            }
            return items;
        } catch (SQLException e) {
            log.error("Failed to take snapshot: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to take snapshot", e);
        }
    }

    @Override
    public boolean removeByIndex(int index) {
        if (index < 0) {
            return false;
        }
        
        String selectSQL = "SELECT id FROM " + tableName + " ORDER BY id LIMIT 1 OFFSET ?";
        String deleteSQL = "DELETE FROM " + tableName + " WHERE id = ?";
        
        try (PreparedStatement selectStmt = connection.prepareStatement(selectSQL)) {
            selectStmt.setInt(1, index);
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSQL)) {
                        deleteStmt.setLong(1, id);
                        return deleteStmt.executeUpdate() > 0;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            log.error("Failed to remove by index: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by index", e);
        }
    }

    @Override
    public int removeByIndices(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return 0;
        }
        
        // Get all IDs first
        String selectSQL = "SELECT id FROM " + tableName + " ORDER BY id";
        List<Long> allIds = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(selectSQL)) {
            while (rs.next()) {
                allIds.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            log.error("Failed to fetch IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch IDs", e);
        }

        // Collect IDs to delete
        List<Long> idsToDelete = new ArrayList<>();
        for (int index : indices) {
            if (index >= 0 && index < allIds.size()) {
                idsToDelete.add(allIds.get(index));
            }
        }

        if (idsToDelete.isEmpty()) {
            return 0;
        }

        // Delete in a single batch query
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < idsToDelete.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String deleteSQL = "DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")";

        try (PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            for (int i = 0; i < idsToDelete.size(); i++) {
                pstmt.setLong(i + 1, idsToDelete.get(i));
            }
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to remove by indices: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by indices", e);
        }
    }

    @Override
    public boolean removeByUID(String uid) {
        if (uid == null) {
            return false;
        }
        String selectSQL = "SELECT id, data FROM " + tableName + " ORDER BY id";
        String deleteSQL = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(selectSQL)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                byte[] data = rs.getBytes("data");
                T item = deserialize(data);
                if (item instanceof RelaySession relaySession) {
                    if (uid.equals(relaySession.getUID())) {
                        try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSQL)) {
                            deleteStmt.setLong(1, id);
                            return deleteStmt.executeUpdate() > 0;
                        }
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            log.error("Failed to remove by UID: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove by UID", e);
        }
    }

    @Override
    public int removeByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }
        
        Set<String> uidSet = new HashSet<>(uids);
        String selectSQL = "SELECT id, data FROM " + tableName + " ORDER BY id";
        List<Long> idsToDelete = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(selectSQL)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                byte[] data = rs.getBytes("data");
                T item = deserialize(data);
                if (item instanceof RelaySession relaySession) {
                    if (uidSet.contains(relaySession.getUID())) {
                        idsToDelete.add(id);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch items for UID removal: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch items for UID removal", e);
        }

        if (idsToDelete.isEmpty()) {
            return 0;
        }

        // Delete in a single batch query
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < idsToDelete.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String deleteSQL = "DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")";

        try (PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            for (int i = 0; i < idsToDelete.size(); i++) {
                pstmt.setLong(i + 1, idsToDelete.get(i));
            }
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete by UIDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete by UIDs", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM " + tableName;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            log.error("Failed to clear queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear queue", e);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debug("{} queue database connection closed", getDatabaseType());
            } catch (SQLException e) {
                log.warn("Error closing {} connection: {}", getDatabaseType(), e.getMessage());
            }
        }
    }

    /**
     * Serialize an object to byte array using Java serialization.
     *
     * @param item Item to serialize
     * @return Serialized byte array
     */
    private byte[] serialize(T item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(item);
            return bos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to serialize item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    /**
     * Deserialize a byte array to object using Java deserialization.
     *
     * @param data Byte array to deserialize
     * @return Deserialized object
     */
    @SuppressWarnings("unchecked")
    private T deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            log.error("Failed to deserialize item: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }

    /**
     * Database configuration holder.
     */
    protected static class DBConfig {
        final String jdbcUrl;
        final String username;
        final String password;
        final String tableName;

        /**
         * Creates a new database configuration.
         *
         * @param jdbcUrl   JDBC connection URL
         * @param username  Database username
         * @param password  Database password
         * @param tableName Table name for queue storage
         */
        DBConfig(String jdbcUrl, String username, String password, String tableName) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.tableName = tableName;
        }
    }
}
