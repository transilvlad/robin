package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by PostgreSQL.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class QueuePgSQL<T extends Serializable> implements QueueDatabase<T> {

    private static final Logger log = LogManager.getLogger(QueuePgSQL.class);

    private Connection connection;
    private final String tableName;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * Constructs a new QueuePgSQL instance.
     * Configuration is loaded from Config.getServer().getQueue().getMapProperty("queuePgSQL").
     */
    public QueuePgSQL() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig pgSQLConfig = new BasicConfig(queueConfig.getMapProperty("queuePgSQL"));
        
        this.jdbcUrl = pgSQLConfig.getStringProperty("jdbcUrl", "jdbc:postgresql://localhost:5432/robin");
        this.username = pgSQLConfig.getStringProperty("username", "robin");
        this.password = pgSQLConfig.getStringProperty("password", "");
        this.tableName = pgSQLConfig.getStringProperty("tableName", "queue");
    }

    /**
     * Initialize the database connection and create table if needed.
     */
    @Override
    public void initialize() {
        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            createTableIfNotExists();
            log.info("PostgreSQL queue database initialized: table={}", tableName);
        } catch (SQLException e) {
            log.error("Failed to initialize PostgreSQL queue database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize PostgreSQL queue database", e);
        }
    }

    /**
     * Create the queue table if it doesn't exist.
     */
    private void createTableIfNotExists() throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "data BYTEA NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            log.debug("Queue table '{}' checked/created", tableName);
        }
    }

    /**
     * Add an item to the tail of the queue.
     *
     * @param item The item to enqueue
     */
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

    /**
     * Remove and return the head of the queue, or null if empty.
     */
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
                
                // Delete the item
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

    /**
     * Peek at the head without removing.
     */
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

    /**
     * Check if the queue is empty.
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Get the size of the queue.
     */
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

    /**
     * Take a snapshot copy of current values for read-only inspection.
     */
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

    /**
     * Remove an item from the queue by index (0-based).
     */
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

    /**
     * Remove items from the queue by indices (0-based).
     */
    @Override
    public int removeByIndices(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return 0;
        }
        
        int removed = 0;
        List<Integer> sortedIndices = new ArrayList<>(indices);
        sortedIndices.sort(Integer::compareTo);
        
        for (int i = sortedIndices.size() - 1; i >= 0; i--) {
            if (removeByIndex(sortedIndices.get(i))) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Remove an item from the queue by UID (for RelaySession).
     */
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
                
                if (item instanceof RelaySession) {
                    RelaySession relaySession = (RelaySession) item;
                    if (uid.equals(relaySession.getSession().getUID())) {
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

    /**
     * Remove items from the queue by UIDs (for RelaySession).
     */
    @Override
    public int removeByUIDs(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return 0;
        }
        
        int removed = 0;
        for (String uid : uids) {
            if (removeByUID(uid)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Clear all items from the queue.
     */
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

    /**
     * Close the database connection.
     */
    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debug("PostgreSQL queue database connection closed");
            } catch (SQLException e) {
                log.warn("Error closing PostgreSQL connection: {}", e.getMessage());
            }
        }
    }

    /**
     * Serialize an object to byte array.
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
     * Deserialize a byte array to object.
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
}
