package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for chaos headers functionality.
 */
class ChaosHeadersIntegrationTest {

    private File tempEmailFile;
    private Connection connection;

    @BeforeAll
    static void beforeAll() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @BeforeEach
    void setUp() throws IOException {
        tempEmailFile = File.createTempFile("chaos-integration-", ".eml");
        tempEmailFile.deleteOnExit();
        
        Session session = new Session();
        connection = new ConnectionMock(session);
        MessageEnvelope envelope = new MessageEnvelope().addRcpt("tony@example.com");
        connection.getSession().addEnvelope(envelope);
    }

    @Test
    @DisplayName("Chaos headers are parsed correctly when enabled")
    void chaosHeadersAreParsedWhenEnabled() throws IOException {
        // Create an email with chaos header
        writeEmailWithChaosHeader(
                "X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true"
        );

        // Parse the email
        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            // Verify the header was parsed
            assertTrue(parser.getHeaders().getAll("X-Robin-Chaos").size() > 0, 
                    "Should have chaos header");
        }
    }

    @Test
    @DisplayName("Chaos headers with multiple parameters are parsed correctly")
    void chaosHeadersWithMultipleParametersAreParsed() throws IOException {
        // Create an email with chaos header
        writeEmailWithChaosHeader(
                "X-Robin-Chaos: DovecotLdaClient; recipient=tony@example.com; exitCode=1; message=\"storage full\""
        );

        // Parse the email
        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            // Verify the header was parsed
            assertEquals(1, parser.getHeaders().getAll("X-Robin-Chaos").size(), 
                    "Should have one chaos header");
        }
    }

    @Test
    @DisplayName("LocalStorageClient processes chaos headers when enabled")
    void localStorageClientProcessesChaosHeaders() throws Exception {
        // Verify chaos headers are enabled in test configuration
        ServerConfig config = Config.getServer();
        assertTrue(config.isChaosHeaders(), "Chaos headers should be enabled in test configuration");

        try {
            // Create an email with chaos header that bypasses AVStorageProcessor
            writeEmailWithChaosHeader(
                    "X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true"
            );

            LocalStorageClient client = new LocalStorageClient()
                    .setConnection(connection)
                    .setExtension("eml");

            // Write content to the stream including chaos header
            client.getStream().write("From: test@example.com\r\n".getBytes(StandardCharsets.UTF_8));
            client.getStream().write("To: recipient@example.com\r\n".getBytes(StandardCharsets.UTF_8));
            client.getStream().write("Subject: Test with chaos header\r\n".getBytes(StandardCharsets.UTF_8));
            client.getStream().write("X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true\r\n".getBytes(StandardCharsets.UTF_8));
            client.getStream().write("\r\nTest body\r\n".getBytes(StandardCharsets.UTF_8));

            // The save() method should complete successfully.
            // With chaos headers enabled, AVStorageProcessor will be bypassed and return true.
            boolean result = client.save();
            assertTrue(result, "Save should succeed with chaos header bypassing AVStorageProcessor");

            // Verify the file was created
            File savedFile = new File(client.getFile());
            assertTrue(savedFile.exists(), "Email file should be saved to disk");

            // Cleanup
            if (savedFile.exists()) {
                savedFile.delete();
            }
        } finally {
            // Config is loaded from test resources and doesn't need restoration
        }
    }

    /**
     * Writes an email file with chaos header.
     *
     * @param chaosHeader The chaos header to write.
     * @throws IOException If writing fails.
     */
    private void writeEmailWithChaosHeader(String chaosHeader) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(tempEmailFile)) {
            fos.write("From: sender@example.com\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("To: recipient@example.com\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Subject: Test with chaos header\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write((chaosHeader + "\r\n").getBytes(StandardCharsets.UTF_8));
            fos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Test email body\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }
}
