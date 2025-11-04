package com.mimecast.robin.mime.headers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeaderWrangler class.
 */
class HeaderWranglerTest {

    static final String dir = "src/test/resources/";

    @Test
    @DisplayName("Add tag to simple subject header")
    void tagSimpleSubject() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("Subject: [SPAM] Test Email"), "Subject should be tagged");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Add tag to encoded subject header")
    void tagEncodedSubject() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: =?UTF-8?B?VGVzdCBFbWFpbA==?=\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[SPAM] =?"), "Subject should contain tag before encoded word");
        assertTrue(resultStr.contains("[SPAM]"), "Tag should be present");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Add custom header after existing headers")
    void addCustomHeader() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("X-Spam-Score: 5.0"), "Custom header should be added");
        assertTrue(resultStr.contains("Subject: Test Email"), "Subject should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");

        // Ensure X-Spam-Score appears before the blank line separating headers from body.
        int headerEnd = resultStr.indexOf("\r\n\r\n");
        int xSpamIndex = resultStr.indexOf("X-Spam-Score");
        assertTrue(xSpamIndex < headerEnd, "X-Spam-Score should be in header section");
    }

    @Test
    @DisplayName("Add tag and custom header together")
    void tagAndAddHeader() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
        wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("Subject: [SPAM] Test Email"), "Subject should be tagged");
        assertTrue(resultStr.contains("X-Spam-Score: 5.0"), "Custom header should be added");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Process lipsum.eml with subject tag")
    void processLipsumWithTag() throws IOException {
        byte[] emailBytes = Files.readAllBytes(Paths.get(dir + "mime/lipsum.eml"));

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SUSPICIOUS]"));

        byte[] result = wrangler.process(emailBytes);
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("Subject: [SUSPICIOUS] Lipsum"), "Subject should be tagged with [SUSPICIOUS]");
        assertTrue(resultStr.contains("From: <{$MAILFROM}>"), "From header should be preserved");
        assertTrue(resultStr.contains("Lorem ipsum dolor"), "Body content should be preserved");
    }

    @Test
    @DisplayName("Process pangrams.eml with subject tag and custom header")
    void processPangramsWithTagAndHeader() throws IOException {
        byte[] emailBytes = Files.readAllBytes(Paths.get(dir + "cases/sources/pangrams.eml"));

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[TEST]"));
        wrangler.addHeader(new MimeHeader("X-Spam-Score", "0.5"));

        byte[] result = wrangler.process(emailBytes);
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[TEST]"), "Subject should contain [TEST] tag");
        assertTrue(resultStr.contains("pangram"), "Original subject text should be preserved");
        assertTrue(resultStr.contains("X-Spam-Score: 0.5"), "X-Spam-Score header should be added");
        assertTrue(resultStr.contains("Árvíztűrő tükörfúrógép"), "Body content should be preserved");
    }

    @Test
    @DisplayName("Handle multi-line header values")
    void handleMultiLineHeader() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: This is a very long subject that spans\r\n" +
                "\tmultiple lines for testing\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[LONG]"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[LONG]"), "Tag should be present");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Handle email with MIME boundary in body")
    void handleMimeBoundary() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test\r\n" +
                "Content-Type: multipart/mixed; boundary=\"boundary123\"\r\n" +
                "\r\n" +
                "--boundary123\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Part 1\r\n" +
                "--boundary123--\r\n";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeader(new MimeHeader("X-Custom", "value"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("X-Custom: value"), "Custom header should be added");
        assertTrue(resultStr.contains("--boundary123"), "Boundary should be preserved");
        assertTrue(resultStr.contains("Part 1"), "Content should be preserved");
    }

    @Test
    @DisplayName("Tag case-insensitive header names")
    void tagCaseInsensitiveHeader() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "SUBJECT: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("subject", "[TAG]"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[TAG]"), "Tag should be applied despite case difference");
        assertTrue(resultStr.contains("Test Email"), "Original subject should be preserved");
    }

    @Test
    @DisplayName("Add multiple custom headers")
    void addMultipleHeaders() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test\r\n" +
                "\r\n" +
                "Body";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));
        wrangler.addHeader(new MimeHeader("X-Spam-Status", "Yes"));
        wrangler.addHeader(new MimeHeader("X-Custom-Flag", "true"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("X-Spam-Score: 5.0"), "First header should be added");
        assertTrue(resultStr.contains("X-Spam-Status: Yes"), "Second header should be added");
        assertTrue(resultStr.contains("X-Custom-Flag: true"), "Third header should be added");
    }

    @Test
    @DisplayName("Tag multiple different headers")
    void tagMultipleHeaders() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test\r\n" +
                "X-Original-Sender: original@example.com\r\n" +
                "\r\n" +
                "Body";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[TAG1]"));
        wrangler.addHeaderTag(new HeaderTag("X-Original-Sender", "[TAG2]"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("Subject: [TAG1] Test"), "Subject should be tagged");
        assertTrue(resultStr.contains("X-Original-Sender: [TAG2] original@example.com"), 
                "X-Original-Sender should be tagged");
    }

    @Test
    @DisplayName("Handle empty email body")
    void handleEmptyBody() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test\r\n" +
                "\r\n";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.addHeaderTag(new HeaderTag("Subject", "[TAG]"));
        wrangler.addHeader(new MimeHeader("X-Custom", "value"));

        byte[] result = wrangler.process(email.getBytes(StandardCharsets.UTF_8));
        String resultStr = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[TAG]"), "Tag should be present");
        assertTrue(resultStr.contains("X-Custom: value"), "Custom header should be added");
    }
}
