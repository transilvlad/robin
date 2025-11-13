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
 * MariaDB implementation of QueueDatabase.
 * <p>A persistent FIFO queue backed by MariaDB.
 *
 * @param <T> Type of items stored in the queue, must be Serializable
 */
public class QueueMariaDB<T extends Serializable> implements QueueDatabase<T> {

    private static final Logger log = LogManager.getLogger(QueueMariaDB.class);

    private Connection connection;
    private final String tableName;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * Constructs a new QueueMariaDB instance.
     * Configuration is loaded from Config.getServer().getQueue().getMapProperty("queueMariaDB").
     */
    public QueueMariaDB() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig mariaDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMariaDB"));
        
        this.jdbcUrl = mariaDBConfig.getStringProperty("jdbcUrl", "jdbc:mariadb://localhost:3306/robin");
        this.username = mariaDBConfig.getStringProperty("username", "robin");
        this.password = mariaDBConfig.getStringProperty("password", "");
        this.tableName = mariaDBConfig.getStringProperty("tableName", "queue");
    }

    /**
     * Initialize the database connection and create table if needed.
     */
    @Override
    public void initialize() {
        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            createTableIfNotExists();
            log.info("MariaDB queue database initialized: table={}", tableName);
        } catch (SQLException e) {
            log.error("Failed to initialize MariaDB queue database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize MariaDB queue database", e);
        }
    }

    /**
     * Create the queue table if it doesn't exist.
     */
    private void createTableIfNotExists() throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "data LONGBLOB NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB";
        
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
     * Close the database connection.
     */
    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debug("MariaDB queue database connection closed");
            } catch (SQLException e) {
                log.warn("Error closing MariaDB connection: {}", e.getMessage());
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
