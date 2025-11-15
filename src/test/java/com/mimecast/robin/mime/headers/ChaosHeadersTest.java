package com.mimecast.robin.mime.headers;

import com.mimecast.robin.mime.EmailParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChaosHeaders class.
 */
class ChaosHeadersTest {

    private File tempEmailFile;

    @BeforeEach
    void setUp() throws IOException {
        tempEmailFile = File.createTempFile("chaos-test-", ".eml");
        tempEmailFile.deleteOnExit();
    }

    @Test
    @DisplayName("Parse single chaos header successfully")
    void parseSingleChaosHeader() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: LocalStorageClient; call=AVStorageProcessor"
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            assertTrue(chaosHeaders.hasHeaders(), "Should have chaos headers");
            assertEquals(1, chaosHeaders.getHeaders().size(), "Should have one chaos header");

            List<MimeHeader> localHeaders = chaosHeaders.getByValue("LocalStorageClient");
            assertEquals(1, localHeaders.size(), "Should find one header for LocalStorageClient");

            MimeHeader header = localHeaders.get(0);
            assertEquals("LocalStorageClient", header.getCleanValue(), "Clean value should be LocalStorageClient");
            assertEquals("AVStorageProcessor", header.getParameter("call"), "Call parameter should be AVStorageProcessor");
        }
    }

    @Test
    @DisplayName("Parse multiple chaos headers")
    void parseMultipleChaosHeaders() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: LocalStorageClient; call=AVStorageProcessor",
                "X-Robin-Chaos: DovecotLdaClient; recipient=tony@example.com; result=\"1:storage full\""
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            assertTrue(chaosHeaders.hasHeaders(), "Should have chaos headers");
            assertEquals(2, chaosHeaders.getHeaders().size(), "Should have two chaos headers");

            List<MimeHeader> localHeaders = chaosHeaders.getByValue("LocalStorageClient");
            assertEquals(1, localHeaders.size(), "Should find one header for LocalStorageClient");

            List<MimeHeader> dovecotHeaders = chaosHeaders.getByValue("DovecotLdaClient");
            assertEquals(1, dovecotHeaders.size(), "Should find one header for DovecotLdaClient");

            MimeHeader dovecotHeader = dovecotHeaders.get(0);
            assertEquals("tony@example.com", dovecotHeader.getParameter("recipient"), "Recipient parameter should match");
            // Note: The quoted value should preserve the colon.
            String result = dovecotHeader.getParameter("result");
            assertNotNull(result, "Result parameter should not be null");
            assertTrue(result.contains(":"), "Result parameter should contain colon");
        }
    }

    @Test
    @DisplayName("Parse multiple chaos headers with same value")
    void parseMultipleSameValueChaosHeaders() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: DovecotLdaClient; recipient=user1@example.com; result=\"0:success\"",
                "X-Robin-Chaos: DovecotLdaClient; recipient=user2@example.com; result=\"1:quota exceeded\""
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            assertTrue(chaosHeaders.hasHeaders(), "Should have chaos headers");
            assertEquals(2, chaosHeaders.getHeaders().size(), "Should have two chaos headers");

            List<MimeHeader> dovecotHeaders = chaosHeaders.getByValue("DovecotLdaClient");
            assertEquals(2, dovecotHeaders.size(), "Should find two headers for DovecotLdaClient");

            // Verify first header
            MimeHeader header1 = dovecotHeaders.get(0);
            assertEquals("user1@example.com", header1.getParameter("recipient"), "First recipient should match");
            assertEquals("0:success", header1.getParameter("result"), "First result should match");

            // Verify second header
            MimeHeader header2 = dovecotHeaders.get(1);
            assertEquals("user2@example.com", header2.getParameter("recipient"), "Second recipient should match");
            assertEquals("1:quota exceeded", header2.getParameter("result"), "Second result should match");
        }
    }

    @Test
    @DisplayName("Handle email with no chaos headers")
    void handleNoChaoHeaders() throws IOException {
        writeEmailWithHeaders(
                "From: sender@example.com",
                "To: recipient@example.com",
                "Subject: Test email"
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            assertFalse(chaosHeaders.hasHeaders(), "Should not have chaos headers");
            assertEquals(0, chaosHeaders.getHeaders().size(), "Should have zero chaos headers");

            List<MimeHeader> localHeaders = chaosHeaders.getByValue("LocalStorageClient");
            assertEquals(0, localHeaders.size(), "Should find no headers for LocalStorageClient");
        }
    }

    @Test
    @DisplayName("Handle null parser")
    void handleNullParser() {
        ChaosHeaders chaosHeaders = new ChaosHeaders(null);

        assertFalse(chaosHeaders.hasHeaders(), "Should not have chaos headers");
        assertEquals(0, chaosHeaders.getHeaders().size(), "Should have zero chaos headers");
    }

    @Test
    @DisplayName("Get by value with null returns empty list")
    void getByValueWithNull() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: LocalStorageClient; call=AVStorageProcessor"
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            List<MimeHeader> headers = chaosHeaders.getByValue(null);
            assertEquals(0, headers.size(), "Should return empty list for null value");
        }
    }

    @Test
    @DisplayName("Get by value is case insensitive")
    void getByValueCaseInsensitive() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: LocalStorageClient; call=AVStorageProcessor"
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            List<MimeHeader> headers1 = chaosHeaders.getByValue("LocalStorageClient");
            List<MimeHeader> headers2 = chaosHeaders.getByValue("localstorageclient");
            List<MimeHeader> headers3 = chaosHeaders.getByValue("LOCALSTORAGECLIENT");

            assertEquals(1, headers1.size(), "Should find header with exact case");
            assertEquals(1, headers2.size(), "Should find header with lowercase");
            assertEquals(1, headers3.size(), "Should find header with uppercase");
        }
    }

    @Test
    @DisplayName("Get by value returns empty list for non-matching value")
    void getByValueNonMatching() throws IOException {
        writeEmailWithHeaders(
                "X-Robin-Chaos: LocalStorageClient; call=AVStorageProcessor"
        );

        try (EmailParser parser = new EmailParser(tempEmailFile.getAbsolutePath()).parse()) {
            ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

            List<MimeHeader> headers = chaosHeaders.getByValue("NonExistentClass");
            assertEquals(0, headers.size(), "Should return empty list for non-matching value");
        }
    }

    /**
     * Writes an email file with the specified headers.
     *
     * @param headers Header lines to write.
     * @throws IOException If writing fails.
     */
    private void writeEmailWithHeaders(String... headers) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(tempEmailFile)) {
            for (String header : headers) {
                fos.write((header + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            fos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Test email body\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }
}
