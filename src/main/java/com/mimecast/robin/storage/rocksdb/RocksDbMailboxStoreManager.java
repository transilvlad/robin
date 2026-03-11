package com.mimecast.robin.storage.rocksdb;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared RocksDB mailbox store lifecycle manager.
 */
public final class RocksDbMailboxStoreManager {
    private static final Map<String, RocksDbMailboxStore> STORES = new ConcurrentHashMap<>();

    private RocksDbMailboxStoreManager() {
        throw new IllegalStateException("Static class");
    }

    public static boolean isEnabled() {
        return getConfig().getBooleanProperty("enabled", false);
    }

    public static RocksDbMailboxStore getConfiguredStore() throws IOException {
        BasicConfig config = getConfig();
        String path = config.getStringProperty("path", "");
        if (path == null || path.isBlank()) {
            throw new IOException("storage.rocksdb.path is required");
        }
        RocksDbMailboxStore existing = STORES.get(path);
        if (existing != null) {
            return existing;
        }
        RocksDbMailboxStore created = new RocksDbMailboxStore(
                path,
                config.getStringProperty("inboxFolder", "Inbox"),
                config.getStringProperty("sentFolder", "Sent")
        );
        RocksDbMailboxStore raced = STORES.putIfAbsent(path, created);
        if (raced != null) {
            created.close();
            return raced;
        }
        return created;
    }

    public static synchronized void closeAll() throws IOException {
        IOException failure = null;
        for (RocksDbMailboxStore store : STORES.values()) {
            try {
                store.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        STORES.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static BasicConfig getConfig() {
        return new BasicConfig(Config.getServer().getStorage().getMapProperty("rocksdb"));
    }
}
