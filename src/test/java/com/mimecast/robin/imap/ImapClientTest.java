package com.mimecast.robin.imap;

import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.assertion.client.imap.ImapExternalClient;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class ImapClientTest {
    private static MimeMessage message(String raw) {
        return new MimeMessage((jakarta.mail.Session) null) {
            @Override
            public void writeTo(OutputStream output) throws IOException {
                output.write(raw.getBytes(StandardCharsets.US_ASCII));
            }
        };
    }

    @Test
    void returnedFilePartsRemainReadableUntilCallerCloses() throws Exception {
        ImapClient client = new ImapClient("imap.example.test", 993, "", "");
        List<MimePart> first = client.extractMimeParts(message("Content-Type: application/octet-stream\r\n\r\nHello"));
        List<MimePart> second = client.extractMimeParts(message("Content-Type: application/octet-stream\r\n\r\nWorld"));
        FileMimePart file = assertInstanceOf(FileMimePart.class, first.getFirst());
        Path path = file.getFile().toPath();
        try {
            client.close();
            assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII), file.getBytes());
            assertArrayEquals(file.getBytes(), file.getBytes());
            assertArrayEquals("World".getBytes(StandardCharsets.US_ASCII), second.getFirst().getBytes());
        } finally {
            file.close();
            for (MimePart part : second) if (part instanceof FileMimePart binary) binary.close();
        }
        assertFalse(Files.exists(path));
    }

    @Test
    void descriptorsKeepWrappersAndUseDecodedContentAndHashes() {
        ImapClient client = new ImapClient("imap.example.test", 993, "", "");
        var result = client.extractParts(message("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: text/plain; charset=Shift_JIS\r\nContent-Transfer-Encoding: base64\r\n\r\ngqA=\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\nSGVsbG8=\r\n"
                + "--b--\r\n"));
        assertEquals(3, result.size());
        assertEquals("", result.getFirst().body());
        assertEquals(0, result.getFirst().size());
        assertTrue(result.getFirst().hashes().isEmpty());
        assertEquals("\u3042", result.get(1).body());
        assertEquals("Hello", result.get(2).body());
        assertEquals(5, result.get(2).size());
        assertEquals("GF+NsyJx/iX1Yab8k4suJkMG7DBO2lGAB9F2SCY4GWk=", result.get(2).hashes().get("sha256"));
    }

    @Test
    void failedSerializationHasNoPartialSuccess() {
        ImapClient client = new ImapClient("imap.example.test", 993, "", "");
        MimeMessage failure = new MimeMessage((jakarta.mail.Session) null) {
            @Override
            public void writeTo(OutputStream output) throws IOException, MessagingException {
                output.write("Content-Type: text/plain\r\n\r\nPartial".getBytes(StandardCharsets.US_ASCII));
                throw new IOException("Synthetic write failure");
            }
        };
        assertTrue(client.extractParts(failure).isEmpty());
        assertTrue(client.extractMimeParts(failure).isEmpty());
        assertTrue(client.extractParts(null).isEmpty());
    }

    @Test
    void actualExternalAssertionMatchesDecodedBodyHeadersAndBinaryHash() throws Exception {
        ImapExternalClient assertion = new ImapExternalClient();
        assertion.setConnection(new Connection(new Session()));
        Method verify = ImapExternalClient.class.getDeclaredMethod(
                "verifyParts", jakarta.mail.Message.class, ImapClient.class, List.class);
        verify.setAccessible(true);
        ImapClient client = new ImapClient("imap.example.test", 993, "", "");
        MimeMessage raw = message("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: text/plain; charset=Shift_JIS\r\nContent-Transfer-Encoding: base64\r\n\r\ngqA=\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\n\r\nHello\r\n--b--\r\n");
        List<Map<String, Object>> specifications = List.of(
                Map.of("headers", List.of(List.of("Content-Type: text/plain")), "body", List.of(List.of("\u3042"))),
                Map.of("headers", List.of(List.of("Content-Type: application/octet-stream")),
                        "hashes", Map.of("sha256", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                                .digest("Hello\r\n".getBytes(StandardCharsets.US_ASCII))))),
                Map.of("headers", List.of(List.of("Content-Type: multipart/mixed"))));
        assertEquals(true, verify.invoke(assertion, raw, client, specifications));
        assertThrows(InvocationTargetException.class, () -> verify.invoke(assertion, raw, client,
                List.of(Map.of("body", List.of(List.of("not present"))))));
        assertThrows(InvocationTargetException.class, () -> verify.invoke(assertion, raw, client,
                List.of(Map.of("hashes", Map.of("sha256", "not a matching hash")))));
    }
}
