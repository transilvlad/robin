package com.mimecast.robin.mime.headers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(emailBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(emailBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("X-Spam-Score: 5.0"), "First header should be added");
        assertTrue(resultStr.contains("X-Spam-Status: Yes"), "Second header should be added");
        assertTrue(resultStr.contains("X-Custom-Flag: true"), "Third header should be added");
    }

    @Test
    @DisplayName("Remove single header case-insensitive")
    void removeSingleHeaderCaseInsensitive() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Spam-Score: 5.0\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("x-spam-score"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Spam-Score"), "X-Spam-Score header should be removed");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("To: recipient@example.com"), "To header should be preserved");
        assertTrue(resultStr.contains("Subject: Test Email"), "Subject header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Remove header with continuation lines")
    void removeHeaderWithContinuationLines() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Long-Header: This is a very long value\r\n" +
                "\tthat continues on the next line\r\n" +
                "\tand even another line\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Long-Header"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Long-Header"), "X-Long-Header should be removed");
        assertFalse(resultStr.contains("that continues"), "Continuation line should be removed");
        assertFalse(resultStr.contains("and even another"), "Second continuation line should be removed");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("Subject: Test Email"), "Subject header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Remove multiple headers")
    void removeMultipleHeaders() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Spam-Score: 5.0\r\n" +
                "X-Spam-Status: Yes\r\n" +
                "Subject: Test Email\r\n" +
                "X-Custom-Flag: true\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Spam-Score", "X-Spam-Status", "X-Custom-Flag"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Spam-Score"), "X-Spam-Score should be removed");
        assertFalse(resultStr.contains("X-Spam-Status"), "X-Spam-Status should be removed");
        assertFalse(resultStr.contains("X-Custom-Flag"), "X-Custom-Flag should be removed");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("To: recipient@example.com"), "To header should be preserved");
        assertTrue(resultStr.contains("Subject: Test Email"), "Subject header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Combine removal with tagging")
    void combineRemovalWithTagging() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Old-Header: old value\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Old-Header"));
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Old-Header"), "X-Old-Header should be removed");
        assertTrue(resultStr.contains("Subject: [SPAM] Test Email"), "Subject should be tagged");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Combine removal with appending")
    void combineRemovalWithAppending() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Old-Header: old value\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Old-Header"));
        wrangler.addHeader(new MimeHeader("X-New-Header", "new value"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Old-Header"), "X-Old-Header should be removed");
        assertTrue(resultStr.contains("X-New-Header: new value"), "X-New-Header should be added");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("Subject: Test Email"), "Subject header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Combine removal, tagging, and appending")
    void combineRemovalTaggingAndAppending() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "X-Old-Header: old value\r\n" +
                "Subject: Test Email\r\n" +
                "\r\n" +
                "Body content";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Old-Header"));
        wrangler.addHeaderTag(new HeaderTag("Subject", "[TAGGED]"));
        wrangler.addHeader(new MimeHeader("X-New-Header", "new value"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Old-Header"), "X-Old-Header should be removed");
        assertTrue(resultStr.contains("Subject: [TAGGED] Test Email"), "Subject should be tagged");
        assertTrue(resultStr.contains("X-New-Header: new value"), "X-New-Header should be added");
        assertTrue(resultStr.contains("From: sender@example.com"), "From header should be preserved");
        assertTrue(resultStr.contains("Body content"), "Body should be preserved");
    }

    @Test
    @DisplayName("Remove header preserves MIME body structure")
    void removeHeaderPreservesMimeBody() throws IOException {
        String email = "From: sender@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Test\r\n" +
                "X-Remove-Me: value\r\n" +
                "Content-Type: multipart/mixed; boundary=\"boundary123\"\r\n" +
                "\r\n" +
                "--boundary123\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Part 1\r\n" +
                "--boundary123--\r\n";

        HeaderWrangler wrangler = new HeaderWrangler();
        wrangler.removeHeaders(List.of("X-Remove-Me"));

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(resultStr.contains("X-Remove-Me"), "X-Remove-Me header should be removed");
        assertTrue(resultStr.contains("--boundary123"), "Boundary should be preserved");
        assertTrue(resultStr.contains("Part 1"), "Content should be preserved");
        assertTrue(resultStr.contains("Content-Type: multipart/mixed"), "Content-Type header should be preserved");
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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

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

        ByteArrayInputStream input = new ByteArrayInputStream(email.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        wrangler.process(input, output);
        String resultStr = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(resultStr.contains("[TAG]"), "Tag should be present");
        assertTrue(resultStr.contains("X-Custom: value"), "Custom header should be added");
    }
}
