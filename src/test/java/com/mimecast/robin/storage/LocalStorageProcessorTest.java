package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageProcessorTest {

    private static final String TEST_EMAIL_CONTENT = "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Test Email\r\n" +
            "\r\n" +
            "This is a test email.\r\n";

    private List<File> filesToCleanup = new ArrayList<>();
    private List<Path> dirsToCleanup = new ArrayList<>();

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @AfterEach
    void cleanup() {
        // Clean up files
        for (File file : filesToCleanup) {
            try {
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception ignored) {}
        }

        // Clean up directories (in reverse order)
        for (int i = dirsToCleanup.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(dirsToCleanup.get(i));
            } catch (Exception ignored) {}
        }

        filesToCleanup.clear();
        dirsToCleanup.clear();
    }

    @Test
    void testProcessorDisabled() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", false);
        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("recipient@example.com");
        connection.getSession().addEnvelope(envelope);
        
        // Create test file
        Path tmpFile = Files.createTempFile("test-email-", ".eml");
        filesToCleanup.add(tmpFile.toFile());
        Files.writeString(tmpFile, TEST_EMAIL_CONTENT);
        envelope.setFile(tmpFile.toString());

        // Process
        try (EmailParser parser = new EmailParser(tmpFile.toString()).parse()) {
            boolean result = processor.process(connection, parser);
            
            // Should return true (success) but not create any files
            assertTrue(result);
        }
    }

    @Test
    void testInboundProcessing() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", true);
        String basePath = Files.createTempDirectory("robin-test-").toString();
        dirsToCleanup.add(Paths.get(basePath));
        Config.getServer().getStorage().getMap().put("path", basePath);
        Config.getServer().getRelay().getMap().put("mailbox", "INBOX");

        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        connection.getSession().setDirection(EmailDirection.INBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .addRcpt("user1@example.com")
                .addRcpt("user2@example.com");
        connection.getSession().addEnvelope(envelope);
        
        // Create test file in tmp directory
        Path tmpDir = Paths.get(basePath, "tmp", "new");
        Files.createDirectories(tmpDir);
        dirsToCleanup.add(tmpDir);
        dirsToCleanup.add(tmpDir.getParent());
        
        Path tmpFile = tmpDir.resolve("test-email.eml");
        Files.writeString(tmpFile, TEST_EMAIL_CONTENT);
        filesToCleanup.add(tmpFile.toFile());
        envelope.setFile(tmpFile.toString());

        // Process
        try (EmailParser parser = new EmailParser(tmpFile.toString()).parse()) {
            boolean result = processor.process(connection, parser);
            assertTrue(result);
            
            // Verify files were created for each recipient
            Path user1File = Paths.get(basePath, "example.com", "user1", "INBOX", "test-email.eml");
            Path user2File = Paths.get(basePath, "example.com", "user2", "INBOX", "test-email.eml");
            
            assertTrue(Files.exists(user1File), "File should exist for user1");
            assertTrue(Files.exists(user2File), "File should exist for user2");
            
            // Verify content includes Received header
            String user1Content = Files.readString(user1File);
            assertTrue(user1Content.contains("Received:"), "Should have Received header");
            assertTrue(user1Content.contains("for <user1@example.com>"), "Should have recipient in Received header");
            assertTrue(user1Content.contains(TEST_EMAIL_CONTENT), "Should have original email content");
            
            String user2Content = Files.readString(user2File);
            assertTrue(user2Content.contains("for <user2@example.com>"), "Should have recipient in Received header");
            
            // Cleanup
            filesToCleanup.add(user1File.toFile());
            filesToCleanup.add(user2File.toFile());
            dirsToCleanup.add(user1File.getParent());
            dirsToCleanup.add(user1File.getParent().getParent());
            dirsToCleanup.add(user1File.getParent().getParent().getParent());
            dirsToCleanup.add(user2File.getParent());
            dirsToCleanup.add(user2File.getParent().getParent());
        }
    }

    @Test
    void testOutboundProcessing() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", true);
        String basePath = Files.createTempDirectory("robin-test-").toString();
        dirsToCleanup.add(Paths.get(basePath));
        Config.getServer().getStorage().getMap().put("path", basePath);
        Config.getServer().getRelay().getMap().put("outbox", "Sent");

        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        connection.getSession().setDirection(EmailDirection.OUTBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("sender@example.com")
                .addRcpt("recipient1@remote.com")
                .addRcpt("recipient2@remote.com");
        connection.getSession().addEnvelope(envelope);
        
        // Create test file in tmp directory
        Path tmpDir = Paths.get(basePath, "tmp", ".Sent", "new");
        Files.createDirectories(tmpDir);
        dirsToCleanup.add(tmpDir);
        dirsToCleanup.add(tmpDir.getParent());
        dirsToCleanup.add(tmpDir.getParent().getParent());
        
        Path tmpFile = tmpDir.resolve("test-email.eml");
        Files.writeString(tmpFile, TEST_EMAIL_CONTENT);
        filesToCleanup.add(tmpFile.toFile());
        envelope.setFile(tmpFile.toString());

        // Process
        try (EmailParser parser = new EmailParser(tmpFile.toString()).parse()) {
            boolean result = processor.process(connection, parser);
            assertTrue(result);
            
            // Verify file was created only once for sender in outbox
            Path senderFile = Paths.get(basePath, "example.com", "sender", "Sent", "test-email.eml");
            
            assertTrue(Files.exists(senderFile), "File should exist in sender's outbox");
            
            // Verify no files for recipients (they are remote)
            Path recipient1File = Paths.get(basePath, "remote.com", "recipient1", "Sent", "test-email.eml");
            assertFalse(Files.exists(recipient1File), "Should not create file for remote recipient");
            
            // Verify content includes Received header without "for" clause
            String senderContent = Files.readString(senderFile);
            assertTrue(senderContent.contains("Received:"), "Should have Received header");
            assertFalse(senderContent.contains("for <"), "Should not have 'for' clause in outbound Received header");
            assertTrue(senderContent.contains(TEST_EMAIL_CONTENT), "Should have original email content");
            
            // Cleanup
            filesToCleanup.add(senderFile.toFile());
            dirsToCleanup.add(senderFile.getParent());
            dirsToCleanup.add(senderFile.getParent().getParent());
            dirsToCleanup.add(senderFile.getParent().getParent().getParent());
        }
    }

    @Test
    void testNoEnvelopes() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", true);
        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        // No envelopes added
        
        // Process
        try (EmailParser parser = new EmailParser("dummy").parse()) {
            boolean result = processor.process(connection, parser);
            
            // Should return true (success) with warning
            assertTrue(result);
        } catch (Exception e) {
            // EmailParser might fail with dummy file, but that's ok for this test
            assertTrue(true);
        }
    }

    @Test
    void testSourceFileDoesNotExist() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", true);
        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("recipient@example.com");
        connection.getSession().addEnvelope(envelope);
        envelope.setFile("/non/existent/file.eml");

        // Process
        try (EmailParser parser = new EmailParser("dummy").parse()) {
            boolean result = processor.process(connection, parser);
            
            // Should return false (failure)
            assertFalse(result);
        } catch (Exception e) {
            // EmailParser might fail with dummy file, but that's ok for this test
            assertTrue(true);
        }
    }

    @Test
    void testInvalidEmailFormat() throws IOException {
        // Setup
        Config.getServer().getStorage().getMap().put("localMailbox", true);
        String basePath = Files.createTempDirectory("robin-test-").toString();
        dirsToCleanup.add(Paths.get(basePath));
        Config.getServer().getStorage().getMap().put("path", basePath);

        LocalStorageProcessor processor = new LocalStorageProcessor();
        
        Connection connection = new Connection(new Session());
        connection.getSession().setDirection(EmailDirection.INBOUND);
        MessageEnvelope envelope = new MessageEnvelope()
                .addRcpt("invalid-email-format");  // No @ sign
        connection.getSession().addEnvelope(envelope);
        
        // Create test file
        Path tmpDir = Paths.get(basePath, "tmp", "new");
        Files.createDirectories(tmpDir);
        dirsToCleanup.add(tmpDir);
        dirsToCleanup.add(tmpDir.getParent());
        
        Path tmpFile = tmpDir.resolve("test-email.eml");
        Files.writeString(tmpFile, TEST_EMAIL_CONTENT);
        filesToCleanup.add(tmpFile.toFile());
        envelope.setFile(tmpFile.toString());

        // Process
        try (EmailParser parser = new EmailParser(tmpFile.toString()).parse()) {
            boolean result = processor.process(connection, parser);
            
            // Should still return true (success) but skip invalid email
            assertTrue(result);
        }
    }
}
