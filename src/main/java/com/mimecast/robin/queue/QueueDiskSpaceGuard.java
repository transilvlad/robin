package com.mimecast.robin.queue;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * Disk-space guard for queue and message durability paths.
 */
public final class QueueDiskSpaceGuard {
    private static final long DEFAULT_MIN_USABLE_BYTES = 1024L * 1024L * 1024L;

    private QueueDiskSpaceGuard() {
        throw new IllegalStateException("Utility class");
    }

    public static void requireWritableStorageAndQueueSpace() throws IOException {
        if (!isEnabled()) {
            return;
        }
        requireUsableSpace(storagePath(), "message storage");
        if (isMapDbEnabled()) {
            requireUsableSpace(queueFilePath().getParent(), "MapDB queue");
        }
    }

    public static void requireWritableQueueSpace(Path queueFile) throws IOException {
        if (!isEnabled()) {
            return;
        }
        Path path = queueFile != null ? queueFile.getParent() : queueFilePath().getParent();
        requireUsableSpace(path, "MapDB queue");
    }

    public static long minUsableBytes() {
        return diskSafetyConfig().getLongProperty("minUsableBytes", DEFAULT_MIN_USABLE_BYTES);
    }

    private static boolean isEnabled() {
        return diskSafetyConfig().getBooleanProperty("enabled", true);
    }

    private static boolean isMapDbEnabled() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        if (!queueConfig.getMap().containsKey("queueMapDB")) {
            return false;
        }
        return new BasicConfig(queueConfig.getMapProperty("queueMapDB")).getBooleanProperty("enabled", true);
    }

    private static Path queueFilePath() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        BasicConfig mapDBConfig = new BasicConfig(queueConfig.getMapProperty("queueMapDB"));
        return Path.of(mapDBConfig.getStringProperty("queueFile", "/usr/local/robin/relayQueue.db"));
    }

    private static Path storagePath() {
        return Path.of(Config.getServer().getStorage().getStringProperty("path", "/tmp/store"));
    }

    private static BasicConfig diskSafetyConfig() {
        BasicConfig queueConfig = Config.getServer().getQueue();
        if (queueConfig.getMap().containsKey("diskSafety")) {
            return new BasicConfig(queueConfig.getMapProperty("diskSafety"));
        }
        return new BasicConfig(new HashMap<>());
    }

    private static void requireUsableSpace(Path path, String purpose) throws IOException {
        Path checkedPath = nearestExistingPath(path);
        long minUsable = minUsableBytes();
        if (minUsable <= 0) {
            return;
        }

        long usable = checkedPath.toFile().getUsableSpace();
        if (usable < minUsable) {
            throw new IOException(String.format(
                    "Insufficient disk space for %s at %s: usable=%d bytes, required=%d bytes",
                    purpose,
                    checkedPath,
                    usable,
                    minUsable
            ));
        }
    }

    private static Path nearestExistingPath(Path path) {
        Path current = path;
        while (current != null && !current.toFile().exists()) {
            current = current.getParent();
        }
        return current != null ? current : Path.of(".");
    }
}
