package com.mimecast.robin.queue;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class QueueFilesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void before() throws Exception {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void persistEnvelopeFilesDoesNotRetargetEnvelopeWhenDiskSafetyFails() throws Exception {
        Object originalPath = Config.getServer().getStorage().getMap().get("path");
        Object originalDiskSafety = Config.getServer().getQueue().getMap().get("diskSafety");
        Path storagePath = Files.createDirectory(tempDir.resolve("storage"));
        Path source = Files.writeString(tempDir.resolve("source.eml"), "Subject: test\r\n\r\nbody\r\n");
        Config.getServer().getStorage().getMap().put("path", storagePath.toString());
        Config.getServer().getQueue().getMap().put("diskSafety", Map.of(
                "enabled", true,
                "minUsableBytes", Long.MAX_VALUE
        ));

        try {
            MessageEnvelope envelope = new MessageEnvelope().setFile(source.toString());
            RelaySession relaySession = new RelaySession(new Session().addEnvelope(envelope));

            assertFalse(QueueFiles.persistEnvelopeFiles(relaySession));
            assertEquals(source.toString(), envelope.getFile());
            assertFalse(Files.exists(storagePath.resolve("queue")));
        } finally {
            restoreConfigValue(Config.getServer().getStorage().getMap(), "path", originalPath);
            restoreConfigValue(Config.getServer().getQueue().getMap(), "diskSafety", originalDiskSafety);
        }
    }

    @Test
    void eachQueueJobOwnsItsFileEvenWhenSourceAlreadyQueued() throws Exception {
        Object originalPath = Config.getServer().getStorage().getMap().get("path");
        Config.getServer().getStorage().getMap().put("path", tempDir.toString());
        try {
            Path original = Files.writeString(tempDir.resolve("source.eml"), "Subject: fanout\r\n\r\nbody\r\n");
            RelaySession first = new RelaySession(new Session().addEnvelope(
                    new MessageEnvelope().setFile(original.toString())));
            assertTrue(QueueFiles.persistEnvelopeFiles(first));
            RelaySession second = new RelaySession(new Session().addEnvelope(first.getSession().getEnvelopes().getFirst().clone()));
            assertTrue(QueueFiles.persistEnvelopeFiles(second));
            Path firstFile = Path.of(first.getSession().getEnvelopes().getFirst().getFile());
            Path secondFile = Path.of(second.getSession().getEnvelopes().getFirst().getFile());
            assertNotEquals(firstFile, secondFile);
            assertTrue(QueueFiles.persistEnvelopeFiles(first));
            assertEquals(firstFile.toString(), first.getSession().getEnvelopes().getFirst().getFile());
            Files.delete(firstFile);
            assertEquals(Files.readString(original), Files.readString(secondFile));
        } finally {
            restoreConfigValue(Config.getServer().getStorage().getMap(), "path", originalPath);
        }
    }

    private static void restoreConfigValue(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }
}
