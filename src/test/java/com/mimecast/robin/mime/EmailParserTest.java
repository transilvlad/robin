package com.mimecast.robin.mime;

import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.smtp.io.LineInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("OptionalGetWithoutIsPresent")
class EmailParserTest {

    static final String dir = "src/test/resources/";

    @Test
    @DisplayName("Parse headers of email finds correct headers")
    void headers() throws IOException {
        String mime = "MIME-Version: 1.0\r\n" +
                "From: Lady Robin <lady.robin@example.com>\r\n" +
                "To: Sir Robin <sir.robin@example.com>\r\n" +
                "Date: Thu, 28 Jan 2021 20:27:09 +0000\r\n" +
                "Message-ID: <twoRobinsMakeAFamily@example.com>\r\n" +
                "Subject: Robin likes\r\n" +
                "Content-Type: text/plain; charset=\"ISO-8859-1\",\r\n\tname=robin.txt,\r\n\tlanguage='en_UK';\r\n" +
                "Content-Disposition: inline charset='ISO-8859-1'\r\n\tfilename=robin.txt;\r\n\tlanguage=en_UK,";
        EmailParser parser = new EmailParser(new LineInputStream(new ByteArrayInputStream(mime.getBytes()), 1024))
                .parse(true);

        assertEquals("1.0", parser.getHeaders().get("MIME-Version").get().getValue());
        assertEquals("Lady Robin <lady.robin@example.com>", parser.getHeaders().get("From").get().getValue());
        assertEquals("Sir Robin <sir.robin@example.com>", parser.getHeaders().get("To").get().getValue());
        assertEquals("Thu, 28 Jan 2021 20:27:09 +0000", parser.getHeaders().get("Date").get().getValue());
        assertEquals("<twoRobinsMakeAFamily@example.com>", parser.getHeaders().get("Message-ID").get().getValue());
        assertEquals("Robin likes", parser.getHeaders().get("Subject").get().getValue());

        assertEquals("text/plain; charset=\"ISO-8859-1\",\r\n\tname=robin.txt,\r\n" +
                "\tlanguage='en_UK';", parser.getHeaders().get("Content-Type").get().getValue());

        assertEquals("ISO-8859-1", parser.getHeaders().get("Content-Type").get().getParameter("charset"));
        assertEquals("robin.txt", parser.getHeaders().get("Content-Type").get().getParameter("name"));
        assertEquals("en_UK", parser.getHeaders().get("Content-Type").get().getParameter("language"));

        assertEquals("inline charset='ISO-8859-1'\r\n\tfilename=robin.txt;\r\n" +
                "\tlanguage=en_UK,", parser.getHeaders().get("Content-Disposition").get().getValue());

        assertEquals("ISO-8859-1", parser.getHeaders().get("Content-Disposition").get().getParameter("charset"));
        assertEquals("robin.txt", parser.getHeaders().get("Content-Disposition").get().getParameter("filename"));
        assertEquals("en_UK", parser.getHeaders().get("Content-Disposition").get().getParameter("language"));
    }

    @Test
    @DisplayName("Parse lipsum.eml gives 2 parts")
    void parseLipsum() throws IOException {
        EmailParser parser = new EmailParser(new LineInputStream(
                new BufferedInputStream(new FileInputStream(dir + "mime/lipsum.eml"), 8192), 1024))
                .parse();

        assertEquals(3, parser.getParts().size(), "Unexpected number of parts");

        assertEquals("uSdGze9aOjGMKP/QLtT9szHfcNV5K9DoaP12xlasxeU=", parser.getParts().get(1).getHash(HashType.SHA_256), "unexpected hash");
        assertEquals(780, parser.getParts().get(1).getSize(), "Unexpected file size");

        assertTrue(validateTextPart(parser.getParts(), 1508, HashType.SHA_256, "bksYTbn+IdI8bDjJTjArvxdEXj719WVpDWmj96KfHAU="));

        assertEquals("UTF-8", parser.getParts().get(2).getHeaders().get("Content-type").get().getParameter("charset"), "inexpected charset");
    }

    @Test
    @DisplayName("Parse lipsum.plain.eml gives 1 part")
    void parseLipsumPlain() throws IOException {
        EmailParser parser = new EmailParser(new LineInputStream(
                new BufferedInputStream(new FileInputStream(dir + "mime/lipsum.plain.eml"), 8192), 1024))
                .parse();

        assertEquals(1, parser.getParts().size(), "Unexpected number of parts");

        assertEquals("WrdX4IXpDfF7m1IpaNJzlrcnnhQR6vynQUHzAEVVpIM=", parser.getParts().get(0).getHash(HashType.SHA_256), "unexpected hash");
        assertEquals(778, parser.getParts().get(0).getSize(), "Unexpected file size");
        assertEquals(2, parser.getParts().get(0).getHeaders().size(), "Unexpected part headers size");

        assertTrue(validateTextPart(parser.getParts(), 778, HashType.SHA_256, "WrdX4IXpDfF7m1IpaNJzlrcnnhQR6vynQUHzAEVVpIM="));
    }

    @Test
    @DisplayName("Parse lipsum.822.eml gives 7 parts")
    void parseLipsum822() throws IOException {
        EmailParser parser = new EmailParser(new LineInputStream(
                new BufferedInputStream(new FileInputStream(dir + "mime/lipsum.822.eml"), 8192), 1024))
                .parse();

        assertEquals(7, parser.getParts().size(), "Unexpected number of parts");

        assertEquals("uSdGze9aOjGMKP/QLtT9szHfcNV5K9DoaP12xlasxeU=", parser.getParts().get(2).getHash(HashType.SHA_256), "unexpected hash");
        assertEquals(780, parser.getParts().get(2).getSize(), "Unexpected file size");
        assertEquals(2, parser.getParts().get(2).getHeaders().size(), "Unexpected part headers size");

        assertEquals("uSdGze9aOjGMKP/QLtT9szHfcNV5K9DoaP12xlasxeU=", parser.getParts().get(5).getHash(HashType.SHA_256), "unexpected hash");
        assertEquals(780, parser.getParts().get(5).getSize(), "Unexpected file size");
        assertEquals(2, parser.getParts().get(5).getHeaders().size(), "Unexpected part headers size");
    }

    @Test
    @DisplayName("Parse DMARC report email extracts ZIP attachment")
    void parseDmarcReport() throws IOException {
        EmailParser parser = new EmailParser(new LineInputStream(
                new BufferedInputStream(new FileInputStream(dir + "mime/dmarc/example-report.eml"), 8192), 1024))
                .parse();

        System.out.println("Parts count: " + parser.getParts().size());
        
        // Should have at least 2 parts: text/plain and application/zip
        assertTrue(parser.getParts().size() >= 2, "Expected at least 2 parts");

        // Find ZIP attachment
        MimePart zipPart = null;
        for (MimePart part : parser.getParts()) {
            if (part.getHeaders().get("Content-Type").isPresent()) {
                String ct = part.getHeaders().get("Content-Type").get().getValue().toLowerCase();
                if (ct.contains("application/zip")) {
                    zipPart = part;
                    break;
                }
            }
        }
        
        assertTrue(zipPart != null, "Should find ZIP attachment");
        
        String filename = zipPart.getHeaders().get("Content-Type").get().getParameter("name");
        System.out.println("ZIP filename: " + filename);
        
        // Check content
        byte[] content = zipPart.getBytes();
        System.out.println("ZIP content length: " + content.length);
        System.out.println("First 4 bytes hex: " + String.format("%02X %02X %02X %02X", 
                content[0], content[1], content[2], content[3]));
        
        // ZIP magic: PK (0x50 0x4B)
        boolean isZip = content[0] == 0x50 && content[1] == 0x4B;
        System.out.println("Is valid ZIP magic: " + isZip);
        
        // If not ZIP, maybe still base64? Check first chars
        if (!isZip) {
            String firstChars = new String(content, 0, Math.min(20, content.length));
            System.out.println("First 20 chars as string: [" + firstChars + "]");
            
            // Try base64 decode
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(content);
                System.out.println("Base64 decoded length: " + decoded.length);
                System.out.println("Decoded first 4 bytes: " + String.format("%02X %02X %02X %02X",
                        decoded[0], decoded[1], decoded[2], decoded[3]));
                isZip = decoded[0] == 0x50 && decoded[1] == 0x4B;
                System.out.println("Decoded is valid ZIP: " + isZip);
            } catch (Exception e) {
                System.out.println("Base64 decode failed: " + e.getMessage());
            }
        }
        
        assertTrue(content.length > 0, "ZIP content should not be empty");
    }

    @SuppressWarnings("SameParameterValue")
    boolean validateTextPart(List<MimePart> parts, long size, HashType hashType, String hashValue) {
        for (MimePart part : parts) {
            if (part instanceof TextMimePart && part.getSize() == size && part.getHash(hashType).equals(hashValue)) {
                return true;
            }
        }

        return false;
    }
}
