package com.mimecast.robin.storage.rocksdb;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Shared RocksDB mailbox store lifecycle manager.
 */
public final class RocksDbMailboxStoreManager {
    private static final Map<String, MailboxStore> STORES = new ConcurrentHashMap<>();
    private static volatile Supplier<MailboxStore> storeFactory;

    private RocksDbMailboxStoreManager() {
        throw new IllegalStateException("Static class");
    }

    public static boolean isEnabled() {
        return storeFactory != null || getConfig().getBooleanProperty("enabled", false);
    }

    public static synchronized MailboxStore getConfiguredStore() throws IOException {
        if (storeFactory != null) {
            return storeFactory.get();
        }
        BasicConfig config = getConfig();
        String path = config.getStringProperty("path", "");
        if (path == null || path.isBlank()) {
            throw new IOException("storage.rocksdb.path is required");
        }
        String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
        MailboxStore existing = STORES.get(normalizedPath);
        if (existing != null) {
            return existing;
        }
        MailboxStore created = new RocksDbMailboxStore(
                normalizedPath,
                config.getStringProperty("inboxFolder", "Inbox"),
                config.getStringProperty("sentFolder", "Sent")
        );
        MailboxStore raced = STORES.putIfAbsent(normalizedPath, created);
        if (raced != null) {
            created.close();
            return raced;
        }
        return created;
    }

    public static synchronized void closeAll() throws IOException {
        IOException failure = null;
        for (MailboxStore store : STORES.values()) {
            try {
                store.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        STORES.clear();
        storeFactory = null;
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Sets a custom factory for testing purposes.
     * When set, getConfiguredStore() returns the factory result instead of creating a RocksDbMailboxStore.
     *
     * @param factory the factory supplier, or null to reset to default behavior
     */
    public static synchronized void setStoreFactory(Supplier<MailboxStore> factory) {
        storeFactory = factory;
    }

    private static BasicConfig getConfig() {
        return new BasicConfig(Config.getServer().getStorage().getMapProperty("rocksdb"));
    }
}
