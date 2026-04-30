/**
 * PostgreSQL-backed configuration store.
 *
 * <p>This package provides an optional configuration synchronization mechanism.
 * When enabled via {@code cfg/config.json5}, Robin will ensure the store schema exists
 * and then synchronize local {@code *.json5} files from the database before loading configuration.
 */
package com.mimecast.robin.config.store;

