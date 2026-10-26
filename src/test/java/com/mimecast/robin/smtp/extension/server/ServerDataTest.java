package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.storage.LocalStorageClient;
import com.mimecast.robin.storage.StorageProcessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.naming.ConfigurationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class ServerDataTest {

    @TempDir
    Path tempDirectory;

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @ParameterizedTest
    @CsvSource({
            "false, 1048576, 1, UTC, +0000",
            "true, 1048576, 1, UTC, +0000",
            "false, 0, 1, GMT+05:30, +0530",
            "true, 0, 1, GMT+05:30, +0530",
            "false, 1048576, 2, UTC, +0000",
            "true, 1048576, 2, UTC, +0000",
            "false, 0, 2, GMT+05:30, +0530",
            "true, 0, 2, GMT+05:30, +0530"
    })
    void prependsReceivedHeaderBeforeStorageAndQueueing(boolean bdat, long bufferSize, int recipientCount,
                                                       String timeZone, String expectedOffset) throws Exception {
        TimeZone originalTimeZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault();
        Map<String, Object> storage = Config.getServer().getStorage().getMap();
        Map<String, Object> originalStorage = new HashMap<>(storage);
        List<Callable<StorageProcessor>> originalProcessors = new ArrayList<>(Factories.getStorageProcessors());
        ByteArrayOutputStream savedMessage = new ByteArrayOutputStream();
        String message = "Received: from previous.example.com by sender.example.com; Mon, 26 Oct 2026 18:00:00 +0000\r\n"
                + "From: sender@example.com\r\nSubject: Test\r\n\r\ncaf\u00e9";
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
            Locale.setDefault(Locale.FRANCE);
            storage.put("path", tempDirectory.toString());
            storage.put("enabled", true);
            storage.put("messageBufferMaxBytes", bufferSize);
            Factories.setStorageProcessors(List.of(() -> (connection, parser) -> {
                MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
                savedMessage.write(envelope.readMessageBytes());
                var source = envelope.getMessageSource();
                MessageEnvelope queuedEnvelope = envelope.clone();
                try {
                    RelaySession relay = new RelaySession(Factories.getSession().addEnvelope(queuedEnvelope));
                    assertTrue(QueueFiles.persistEnvelopeFiles(relay));
                    assertArrayEquals(savedMessage.toByteArray(), Files.readAllBytes(Path.of(queuedEnvelope.getFile())));
                } finally {
                    if (source instanceof RefCountedFileMessageSource refCounted) {
                        refCounted.release();
                    }
                }
                return true;
            }));
            ConnectionMock connection = new ConnectionMock(new StringBuilder(message + (bdat ? "" : "\r\n.\r\n")));
            connection.setSocket(new Socket());
            connection.getSession().setDirection(EmailDirection.INBOUND);
            connection.getSession().setEhlo("sender.example.com");
            connection.getSession().setFriendAddr("192.0.2.10");
            connection.getSession().setFriendRdns("sender.example.com");
            connection.getSession().setRdns("192.0.2.20");
            connection.getSession().setStartTls(true);
            connection.setProtocol("TLSv1.3");
            connection.setCipherSuite("TLS_AES_256_GCM_SHA384");
            MessageEnvelope envelope = new MessageEnvelope().setMail("sender@example.com").addRcpt("user@example.com");
            if (recipientCount > 1) {
                envelope.addRcpt("private@example.com");
            }
            connection.getSession().addEnvelope(envelope);

            ZonedDateTime beforeReceipt = ZonedDateTime.now().minusSeconds(1);
            ServerData data = new ServerData().setEmailSizeLimit(messageBytes.length + (bdat ? 0 : 5));
            try {
                assertTrue(data.process(connection, new Verb(bdat ? "BDAT " + messageBytes.length + " LAST" : "DATA")));
            } finally {
                connection.getSession().close();
            }

            String stored = savedMessage.toString(StandardCharsets.UTF_8);
            assertTrue(stored.startsWith("Received: from sender.example.com (sender.example.com [192.0.2.10])\r\n"));
            assertTrue(stored.contains("by " + Config.getServer().getHostname() + " (RobinMTA)\r\n"));
            assertTrue(stored.contains("with ESMTPS (version=TLSv1.3 cipher=TLS_AES_256_GCM_SHA384 bits=256/256)\r\n"));
            assertTrue(stored.contains("id " + connection.getSession().getUID() + "\r\n"));
            assertEquals(recipientCount == 1, stored.contains("for <user@example.com>"));
            assertFalse(stored.contains("private@example.com"));
            int originalOffset = stored.indexOf(message);
            assertTrue(originalOffset > 0);
            assertEquals(message, stored.substring(originalOffset));
            String addedHeader = stored.substring(0, originalOffset);
            assertEquals(1, addedHeader.lines().filter(line -> line.startsWith("Received:")).count());
            String date = addedHeader.lines().reduce((first, second) -> second).orElseThrow().trim();
            assertTrue(date.endsWith(" " + expectedOffset), "Receipt date must use a numeric timezone: " + date);
            ZonedDateTime receiptTime = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
            assertFalse(receiptTime.isBefore(beforeReceipt));
            assertFalse(receiptTime.isAfter(ZonedDateTime.now()));
            assertEquals(messageBytes.length, data.getBytesReceived());
        } finally {
            TimeZone.setDefault(originalTimeZone);
            Locale.setDefault(originalLocale);
            Factories.setStorageProcessors(originalProcessors);
            storage.clear();
            storage.putAll(originalStorage);
        }
    }

    @Test
    void returns451AndClosesStreamWhenReceivedHeaderCannotBeWritten() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean saved = new AtomicBoolean();
        OutputStream failingStream = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Storage write failed");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        Factories.setStorageClient(() -> new LocalStorageClient() {
            @Override
            public OutputStream getStream() {
                return failingStream;
            }

            @Override
            public boolean save() {
                saved.set(true);
                return true;
            }
        });
        try {
            ConnectionMock connection = new ConnectionMock(new StringBuilder("Subject: Test\r\n\r\nBody\r\n.\r\n"));
            connection.setSocket(new Socket());
            connection.getSession().addEnvelope(new MessageEnvelope().addRcpt("user@example.com"));

            assertFalse(new ServerData().process(connection, new Verb("DATA")));
            assertTrue(connection.getOutput().contains("451 4.3.2 Internal server error"));
            assertTrue(closed.get());
            assertFalse(saved.get());
        } finally {
            Factories.setStorageClient(null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blackholedMessageDoesNotOpenStorage(boolean bdat) throws IOException {
        Factories.setStorageClient(() -> {
            throw new AssertionError("Blackholed messages must not open storage");
        });
        try {
            String message = "Subject: Test\r\n\r\nBody";
            ConnectionMock connection = new ConnectionMock(new StringBuilder(message + (bdat ? "" : "\r\n.\r\n")));
            connection.setSocket(new Socket());
            connection.getSession().addEnvelope(new MessageEnvelope().addRcpt("user@example.com").setBlackholed(true));

            ServerData data = new ServerData();
            assertTrue(data.process(connection, new Verb(bdat ? "BDAT " + message.length() + " LAST" : "DATA")));
            assertEquals(message.length(), data.getBytesReceived());
        } finally {
            Factories.setStorageClient(null);
        }
    }

    @Test
    void processAscii() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r\n");
        stringBuilder.append("From: <tony@example.com>\r\n");
        stringBuilder.append("To: <pepper@example.com>\r\n");
        stringBuilder.append("Subject: Lost in space\r\n");
        stringBuilder.append("\r\n");
        stringBuilder.append("Rescue me!\r\n");
        stringBuilder.append(".\r\n\r\n\r\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.enableSmtpAudit("smtp");
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (5 + 4), data.getBytesReceived());
        assertEquals(1, connection.getSmtpAuditContext().getAcceptedMessages());
        assertEquals(data.getBytesReceived(), connection.getSmtpAuditContext().getMessageBytes());
    }

    @Test
    void processAsciiLF() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\n");
        stringBuilder.append("From: <tony@example.com>\n");
        stringBuilder.append("To: <pepper@example.com>\n");
        stringBuilder.append("Subject: Lost in space\n");
        stringBuilder.append("\n");
        stringBuilder.append("Rescue me!\n");
        stringBuilder.append(".\n\n\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.enableSmtpAudit("smtp");
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (3 + 2), data.getBytesReceived());
    }

    @Test
    void processAsciiCR() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r");
        stringBuilder.append("From: <tony@example.com>\r");
        stringBuilder.append("To: <pepper@example.com>\r");
        stringBuilder.append("Subject: Lost in space\r");
        stringBuilder.append("\r");
        stringBuilder.append("Rescue me!\r");
        stringBuilder.append(".\r\r\r");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.enableSmtpAudit("smtp");
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("DATA");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertEquals(SmtpResponses.READY_WILLING_354 + "\r\n", connection.getLine(1));
        assertTrue(connection.getLine(2).startsWith("250 2.0.0 Received OK"), "startsWith(\"250 2.0.0 Received OK\")");
        assertEquals(stringBuilder.toString().length() - (3 + 2), data.getBytesReceived());
    }

    @Test
    void processAsciiReturns451WhenDiskSafetyFails() throws IOException {
        Object originalDiskSafety = Config.getServer().getQueue().getMap().get("diskSafety");
        Config.getServer().getQueue().getMap().put("diskSafety", Map.of(
                "enabled", true,
                "minUsableBytes", Long.MAX_VALUE
        ));
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("MIME-Version: 1.0\r\n");
            stringBuilder.append("From: <tony@example.com>\r\n");
            stringBuilder.append("To: <pepper@example.com>\r\n");
            stringBuilder.append("Subject: Lost in space\r\n");
            stringBuilder.append("\r\n");
            stringBuilder.append("Rescue me!\r\n");
            stringBuilder.append(".\r\n");

            ConnectionMock connection = new ConnectionMock(stringBuilder);
            connection.setSocket(new Socket());
            connection.enableSmtpAudit("smtp");
            connection.getSession().addEnvelope(new MessageEnvelope());
            connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

            boolean process = new ServerData().process(connection, new Verb("DATA"));

            assertFalse(process);
            connection.parseLines();
            assertTrue(connection.getLine(1).startsWith("451 4.3.2 Internal server error"));
            assertEquals(1, connection.getSmtpAuditContext().getRejectedMessages());
            assertEquals(451, connection.getSmtpAuditContext().getLastSmtpStatus());
        } finally {
            if (originalDiskSafety == null) {
                Config.getServer().getQueue().getMap().remove("diskSafety");
            } else {
                Config.getServer().getQueue().getMap().put("diskSafety", originalDiskSafety);
            }
        }
    }

    @Test
    void releaseCompletedEnvelopeSourceReleasesSpoolFile() throws IOException {
        Path file = Files.createTempFile("serverdata-release-", ".eml");
        Files.writeString(file, "body");
        RefCountedFileMessageSource source = new RefCountedFileMessageSource(file);

        ConnectionMock connection = new ConnectionMock(new StringBuilder());
        connection.setSocket(new Socket());
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMessageSource(source);
        connection.getSession().addEnvelope(envelope);

        ServerData data = new ServerData();
        data.connection = connection;

        // A completed message with no async consumers: releasing the receipt's
        // reference must delete the spool file instead of holding it until the
        // connection closes.
        data.releaseCompletedEnvelopeSource();
        assertEquals(0, source.getRefCount());
        assertFalse(Files.exists(file), "completed message spool file must be released");

        // The source/file refs are cleared so Session.close() does not double-release.
        connection.getSession().close();
        assertEquals(0, source.getRefCount(), "Session.close() must not double-release");
    }

    @Test
    void processBinary() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("MIME-Version: 1.0\r\n");
        stringBuilder.append("From: <tony@example.com>\r\n");
        stringBuilder.append("To: <pepper@example.com>\r\n");
        stringBuilder.append("Subject: Lost in space\r\n");
        stringBuilder.append("\r\n");
        stringBuilder.append("Rescue me!\r\n\r\n");

        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(new MessageEnvelope());
        connection.getSession().getEnvelopes().getLast().addRcpt("john@example.com");

        Verb verb = new Verb("BDAT 109 LAST");

        ServerData data = new ServerData();
        boolean process = data.process(connection, verb);

        assertTrue(process);

        connection.parseLines();
        assertTrue(connection.getLine(1).startsWith("250 2.0.0 Chunk OK"), "startsWith(\"250 2.0.0 Chunk OK\")");
        assertEquals(stringBuilder.toString().length() - 2, data.getBytesReceived());
    }
}
