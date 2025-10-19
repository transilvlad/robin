package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Queue file utilities.
 * <p>
 * Ensures that any files referenced by envelopes in a RelaySession are copied to a
 * persistent "queue" subfolder under the configured storage path. This prevents
 * them from being cleaned up on restart.
 */
public final class QueueFiles {
    private static final Logger log = LogManager.getLogger(QueueFiles.class);

    private QueueFiles() {}

    /**
     * Move any envelope files into the storage/queue folder and update their paths.
     * This is idempotent: if a file is already inside the queue folder, it is skipped.
     *
     * @param relaySession RelaySession containing a Session with envelopes.
     */
    public static void persistEnvelopeFiles(RelaySession relaySession) {
        if (relaySession == null || relaySession.getSession() == null) return;
        List<MessageEnvelope> envelopes = relaySession.getSession().getEnvelopes();
        if (envelopes == null || envelopes.isEmpty()) return;

        String storagePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        Path queueDir = Paths.get(storagePath, "queue");
        try {
            Files.createDirectories(queueDir);
        } catch (IOException e) {
            log.error("Unable to create queue folder at {}: {}", queueDir, e.getMessage());
            return;
        }

        for (int i = 0; i < envelopes.size(); i++) {
            MessageEnvelope env = envelopes.get(i);
            String filePath = env != null ? env.getFile() : null;
            if (StringUtils.isBlank(filePath)) {
                continue;
            }

            try {
                Path src = Paths.get(filePath);
                if (!Files.exists(src)) {
                    log.debug("Envelope file does not exist, skipping move: {}", filePath);
                    continue;
                }

                String fileName = src.getFileName().toString();
                Path target = queueDir.resolve(fileName);

                // Ensure unique target if file exists.
                if (Files.exists(target)) {
                    String unique = uniqueName(queueDir, fileName, relaySession.getSession().getUID(), i);
                    target = queueDir.resolve(unique);
                }

                // Try to copy
                try {
                    Files.copy(src, target);
                } catch (IOException ex) {
                    log.debug("Copy failed ({}),  {}", src, ex.getMessage());
                }

                env.setFile(target.toString());
                log.info("Copied envelope file to persistent queue: {} -> {}", src, target);
            } catch (Exception ex) {
                log.error("Failed copying envelope file to queue: {} ({}): {}", filePath, new File(filePath).exists(), ex.getMessage());
            }
        }
    }

    /**
     * Generate a unique filename within the specified directory.
     *
     * @param dir       Target directory Path.
     * @param original  Original filename.
     * @param uid       Session UID for uniqueness.
     * @param index     Envelope index for uniqueness.
     * @return Unique filename as String.
     */
    private static String uniqueName(Path dir, String original, String uid, int index) {
        int dot = original.lastIndexOf('.');
        String base = (dot > 0) ? original.substring(0, dot) : original;
        String ext = (dot > 0) ? original.substring(dot) : "";

        // Try with session UID and envelope index to avoid collision.
        String candidate = base + "." + uid + "." + index + ext;
        if (!Files.exists(dir.resolve(candidate))) return candidate;

        int i = 1;
        while (Files.exists(dir.resolve(base + "." + uid + "." + index + "-" + i + ext))) {
            i++;
        }
        return base + "." + uid + "." + index + "-" + i + ext;
    }
}
