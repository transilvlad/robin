package com.mimecast.robin.mime;

import com.mimecast.robin.smtp.io.LineInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailParserTest {

    @Test
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    void headers() throws IOException {
        String mime = "MIME-Version: 1.0\r\n" +
                "From: Lady Robin <lady.robin@example.com>\r\n" +
                "To: Sir Robin <sir.robin@example.com>\r\n" +
                "Date: Thu, 28 Jan 2021 20:27:09 +0000\r\n" +
                "Message-ID: <twoRobinsMakeAFamily@example.com>\r\n" +
                "Subject: Robin likes\r\n" +
                "Content-Type: text/plain;\r\n\tcharset=\"ISO-8859-1\"\r\n";
        EmailParser parser = new EmailParser(new LineInputStream(new ByteArrayInputStream(mime.getBytes())))
                .parse(true);

        assertEquals("1.0", parser.getHeader("MIME-Version").get().getValue());
        assertEquals("Lady Robin <lady.robin@example.com>", parser.getHeader("From").get().getValue());
        assertEquals("Sir Robin <sir.robin@example.com>", parser.getHeader("To").get().getValue());
        assertEquals("Thu, 28 Jan 2021 20:27:09 +0000", parser.getHeader("Date").get().getValue());
        assertEquals("<twoRobinsMakeAFamily@example.com>", parser.getHeader("Message-ID").get().getValue());
        assertEquals("Robin likes", parser.getHeader("Subject").get().getValue());
        assertEquals("text/plain;\r\n\tcharset=\"ISO-8859-1\"", parser.getHeader("Content-Type").get().getValue());
    }
}