/**
 * A persistent queue for storing emails that could not be delivered.
 *
 * <p>This package provides multiple queue persistence backends for storing messages
 * <br>that could not be relayed. The queue backend is configured in {@code cfg/queue.json5}.
 *
 * <p>The {@link com.mimecast.robin.queue.PersistentQueue} is the main class for the persistent queue.
 * <br>It is used by the server to store emails that could not be delivered.
 *
 * <h2>Supported Backends:</h2>
 * <ul>
 *     <li><b>MapDB</b> - Lightweight, file-based embedded database (default for production)</li>
 *     <li><b>Redis</b> - High-performance in-memory queue with optional disk persistence</li>
 *     <li><b>MariaDB</b> - Robust SQL-based queue with transaction support</li>
 *     <li><b>PostgreSQL</b> - Enterprise-grade queue with ACID compliance</li>
 *     <li><b>InMemory</b> - In-memory queue for tests (default when all backends disabled)</li>
 * </ul>
 *
 * <h2>Backend Selection Priority:</h2>
 * <ol>
 *     <li>MapDB - if {@code queueMapDB.enabled} is {@code true}</li>
 *     <li>Redis - if {@code queueRedis.enabled} is {@code true}</li>
 *     <li>MariaDB - if {@code queueMariaDB.enabled} is {@code true}</li>
 *     <li>PostgreSQL - if {@code queuePgSQL.enabled} is {@code true}</li>
 *     <li>InMemory - fallback when all backends are disabled</li>
 * </ol>
 *
 * <p>All backends implement the {@link com.mimecast.robin.queue.QueueDatabase} interface,
 * <br>providing FIFO queue operations, size checks, snapshots, and item removal capabilities.
 *
 * <p>The {@link com.mimecast.robin.queue.relay.RelayQueueCron} processes the queue periodically
 * <br>with configurable retry scheduling for failed deliveries.
 *
 * @see com.mimecast.robin.queue.PersistentQueue
 * @see com.mimecast.robin.queue.QueueDatabase
 * @see com.mimecast.robin.queue.relay.RelayQueueCron
 */
package com.mimecast.robin.queue;

