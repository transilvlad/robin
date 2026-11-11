package com.mimecast.robin.bots;

import com.mimecast.robin.mime.EmailParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportExtractionTest {
    private static Object extract(Object bot, String method, String message) throws Exception {
        try (EmailParser parser = new EmailParser(new ByteArrayInputStream(message.getBytes(StandardCharsets.US_ASCII))).parse()) {
            Method extraction = bot.getClass().getDeclaredMethod(method, EmailParser.class);
            extraction.setAccessible(true);
            return extraction.invoke(bot, parser);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filename=report", "filename*=UTF-8''report", "filename*=UTF-8''caf%C3%A9",
            "filename*=ISO-8859-1''caf%E9", "filename=\"report+name"})
    void reportBotsDispatchSupportedFilenameForms(String prefix) throws Exception {
        String end = prefix.startsWith("filename=\"") ? "\"" : "";
        String header = "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; ";
        assertArrayEquals("<report/>\r\n".getBytes(StandardCharsets.US_ASCII),
                (byte[]) extract(new DmarcBot(), "extractDmarcReport",
                        header + prefix + ".XML" + end + "\r\n\r\n<report/>\r\n"));
        assertEquals("{}\r\n", extract(new TlsrptBot(), "extractTlsrptReport",
                header + prefix + ".JSON" + end + "\r\n\r\n{}\r\n"));
    }

    @Test
    void typeNameFallbackAndMissingFilenameKeepDispatchContracts() throws Exception {
        assertArrayEquals("<report/>\r\n".getBytes(StandardCharsets.US_ASCII),
                (byte[]) extract(new DmarcBot(), "extractDmarcReport",
                        "Content-Type: application/octet-stream; name*=UTF-8''report.xml\r\n\r\n<report/>\r\n"));
        assertEquals("{}\r\n", extract(new TlsrptBot(), "extractTlsrptReport",
                "Content-Type: application/octet-stream; name*=UTF-8''report.json\r\n\r\n{}\r\n"));
        assertNull(extract(new DmarcBot(), "extractDmarcReport",
                "Content-Type: application/octet-stream\r\n\r\n<report/>\r\n"));
        assertNull(extract(new TlsrptBot(), "extractTlsrptReport",
                "Content-Type: application/octet-stream\r\n\r\n{}\r\n"));
    }

    @Test
    void forensicConsumerUsesOriginalHeaderBlockNotBodyOrDescendant() throws Exception {
        String headers = "From: sender@example.test\r\nSubject: original\r\nX-Note: first\r\n\tsecond\r\n";
        String raw = "Content-Type: multipart/report; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: message/feedback-report\r\n\r\nFeedback-Type: auth-failure\r\n"
                + "--b\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment\r\n\r\n"
                + headers + "\r\nSubject: body only\r\n--b--\r\n";
        Object value = extract(new ForensicBot(), "extractForensicReport", raw);
        assertInstanceOf(Map.class, value);
        Map<?, ?> report = (Map<?, ?>) value;
        assertEquals("auth-failure", report.get("feedbackType"));
        assertEquals(headers, report.get("originalHeaders"));
        assertEquals("original", report.get("subject"));
    }

    @Test
    void forensicHeaderSourcesDoNotMatchSubstringOrOverwriteOuterSource() throws Exception {
        String raw = "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\nContent-Type: message/rfc822\r\n\r\n"
                + "X-Subject: not the subject\r\nSubject: outer\r\n"
                + "Content-Type: multipart/mixed; boundary=inner\r\n\r\n"
                + "--inner\r\nContent-Type: message/rfc822\r\n\r\n"
                + "Subject: inner\r\n\r\nBody\r\n--inner--\r\n--outer--\r\n";
        Map<?, ?> report = (Map<?, ?>) extract(new ForensicBot(), "extractForensicReport", raw);
        assertEquals("outer", report.get("subject"));
        assertFalse(((String) report.get("originalHeaders")).contains("Subject: inner"));
        Map<?, ?> headersOnly = (Map<?, ?>) extract(new ForensicBot(), "extractForensicReport",
                "Content-Type: text/rfc822-headers\r\n\r\nSubject: header source\r\n\r\nSubject: body\r\n");
        assertEquals("header source", headersOnly.get("subject"));
        assertEquals("Subject: header source\r\n", headersOnly.get("originalHeaders"));
    }
}
