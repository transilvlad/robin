package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.storage.LocalStorageClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class HeaderReceiptTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void before() throws Exception {
        Foundation.init("src/test/resources/cfg/");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void invalidHeaderIsRejectedBeforeSaveAndUnreadInputCannotBecomeCommands(boolean bdat,
                                                                            boolean blackholed) throws Exception {
        String message = "From: first@origin.test\r\r\nFrom: second@other.test\r\n\r\nBody";
        AtomicBoolean saved = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayOutputStream raw = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        Factories.setStorageClient(() -> new LocalStorageClient() {
            @Override
            public OutputStream getStream() {
                assertFalse(blackholed);
                return raw;
            }

            @Override
            public boolean save() {
                saved.set(true);
                return true;
            }
        });
        ConnectionMock connection = connection(message + (bdat ? "" : "\r\n.\r\nNOOP\r\n"),
                new MessageEnvelope().addRcpt("recipient@example.test").setBlackholed(blackholed));
        try {
            assertFalse(new ServerData().process(connection,
                    new Verb(bdat ? "BDAT " + message.length() + " LAST" : "DATA")));
            assertTrue(connection.getOutput().contains("554 5.6.0"));
            assertFalse(connection.getOutput().contains("250 "));
            assertFalse(connection.getOutput().contains("451 "));
            assertTrue(connection.getSocket().isClosed());
            assertFalse(saved.get());
            assertEquals(!blackholed, closed.get());
        } finally {
            connection.close();
            Factories.setStorageClient(null);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void legalFramingIsPreservedAndBodyCarriageReturnsAreNotRejected(boolean bdat,
                                                                    boolean lfOnly) throws Exception {
        String eol = lfOnly ? "\n" : "\r\n";
        String message = "From: First <first@origin.test>" + eol + "X-Note: caf\u00e9\u0085" + eol
                + "\tfolded" + eol + eol + "Body\rcontent\u0000\u000b\u007f\rend";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        AtomicBoolean saved = new AtomicBoolean();
        Factories.setStorageClient(() -> new LocalStorageClient() {
            @Override
            public OutputStream getStream() {
                return raw;
            }

            @Override
            public boolean save() {
                saved.set(true);
                return true;
            }
        });
        ConnectionMock connection = connection(message + (bdat ? "" : eol + "." + eol),
                new MessageEnvelope().addRcpt("recipient@example.test"));
        try {
            assertTrue(new ServerData().process(connection,
                    new Verb(bdat ? "BDAT " + bytes.length + " LAST" : "DATA")));
            assertTrue(saved.get());
            byte[] stored = raw.toByteArray();
            assertArrayEquals(bytes, java.util.Arrays.copyOfRange(stored, stored.length - bytes.length,
                    stored.length));
            assertFalse(connection.getSocket().isClosed());
        } finally {
            connection.close();
            Factories.setStorageClient(null);
        }
    }

    @Test
    void bdatCarriesHeaderValidationStateAcrossCommandsAndChecksFinalCr() throws Exception {
        for (String continuation : new String[]{"\n\r\nBody", "\r\nFrom: second@other.test\r\n\r\nBody", ""}) {
            String first = "From: first@origin.test\r";
            ConnectionMock connection = connection(first + continuation,
                    new MessageEnvelope().addRcpt("recipient@example.test").setBlackholed(true));
            try {
                assertTrue(new ServerData().process(connection, new Verb("BDAT " + first.length())));
                boolean valid = continuation.startsWith("\n");
                assertEquals(valid, new ServerData().process(connection,
                        new Verb("BDAT " + continuation.length() + " LAST")));
                assertEquals(!valid, connection.getSocket().isClosed());
                assertEquals(!valid, connection.getOutput().contains("554 5.6.0"));
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void streamingFramingDoesNotIntroduceAnAggregateHeaderSizePolicy() throws Exception {
        String message = "X-Long: " + "x".repeat(1024 * 1024 + 1) + "\r\n\r\nBody";
        ConnectionMock connection = connection(message,
                new MessageEnvelope().addRcpt("recipient@example.test").setBlackholed(true));
        try {
            assertTrue(new ServerData().process(connection, new Verb("BDAT " + message.length() + " LAST")));
        } finally {
            connection.close();
        }
    }

    @Test
    void dataCannotHideAHeaderCrInTheDiscardedTransportTerminator() throws Exception {
        ConnectionMock connection = connection("From: first@origin.test\r.\r",
                new MessageEnvelope().addRcpt("recipient@example.test").setBlackholed(true));
        try {
            assertFalse(new ServerData().process(connection, new Verb("DATA")));
            assertTrue(connection.getOutput().contains("554 5.6.0"));
            assertTrue(connection.getSocket().isClosed());
        } finally {
            connection.close();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1048576})
    void invalidStoredSourceIsDiscardedBeforeProcessorsOrSessionTeardown(long threshold) throws Exception {
        var storage = Config.getServer().getStorage().getMap();
        Object previousPath = storage.put("path", directory.toString());
        Object previousEnabled = storage.put("enabled", true);
        Object previousThreshold = storage.put("messageBufferMaxBytes", threshold);
        var processors = new ArrayList<>(Factories.getStorageProcessors());
        AtomicBoolean processed = new AtomicBoolean();
        ConnectionMock connection = connection("", new MessageEnvelope().addRcpt("recipient@example.test"));
        try {
            Factories.setStorageProcessors(java.util.List.of(() -> (conn, parser) -> {
                processed.set(true);
                return true;
            }));
            LocalStorageClient client = new LocalStorageClient().setConnection(connection).setExtension("eml");
            client.getStream().write("From: first@origin.test\r\r\nFrom: second@other.test\r\n\r\nBody"
                    .getBytes(StandardCharsets.US_ASCII));
            Path file = Path.of(client.getFile());
            assertEquals(threshold == 0, Files.exists(file));
            assertFalse(client.save());
            assertFalse(processed.get());
            assertTrue(connection.getOutput().contains("554 5.6.0"));
            assertFalse(Files.exists(file));
            assertNull(connection.getSession().getEnvelopes().getLast().getMessageSource());
            assertNull(connection.getSession().getEnvelopes().getLast().getFile());
            byte[] replacement = "unrelated replacement".getBytes(StandardCharsets.US_ASCII);
            Files.write(file, replacement);
            client.abort();
            connection.getSession().close();
            connection.getSession().close();
            assertArrayEquals(replacement, Files.readAllBytes(file));
        } finally {
            connection.getSession().close();
            connection.close();
            Factories.setStorageProcessors(processors);
            storage.put("path", previousPath);
            storage.put("enabled", previousEnabled);
            if (previousThreshold == null) storage.remove("messageBufferMaxBytes");
            else storage.put("messageBufferMaxBytes", previousThreshold);
        }
    }

    @Test
    void rejectingASpilledReceiptDiscardsOnlyItsOwnedFile() throws Exception {
        var storage = Config.getServer().getStorage().getMap();
        Object previousPath = storage.put("path", directory.toString());
        Object previousEnabled = storage.put("enabled", true);
        Object previousThreshold = storage.put("messageBufferMaxBytes", 0L);
        ConnectionMock connection = connection("From: first@origin.test\r\r\n\r\nBody",
                new MessageEnvelope().addRcpt("recipient@example.test"));
        LocalStorageClient client = new LocalStorageClient().setConnection(connection).setExtension("eml");
        try {
            Factories.setStorageClient(() -> client);
            assertFalse(new ServerData().process(connection, new Verb("DATA")));
            Path file = Path.of(client.getFile());
            assertFalse(Files.exists(file));
            byte[] replacement = "unrelated replacement".getBytes(StandardCharsets.US_ASCII);
            Files.write(file, replacement);
            client.abort();
            assertArrayEquals(replacement, Files.readAllBytes(file));
        } finally {
            connection.close();
            Factories.setStorageClient(null);
            storage.put("path", previousPath);
            storage.put("enabled", previousEnabled);
            if (previousThreshold == null) storage.remove("messageBufferMaxBytes");
            else storage.put("messageBufferMaxBytes", previousThreshold);
        }
    }

    @Test
    void materializedLastChunkIsCheckedEvenAfterTheLogicalHeaderSeparator() throws Exception {
        var storage = Config.getServer().getStorage().getMap();
        Object previousPath = storage.put("path", directory.toString());
        Object previousEnabled = storage.put("enabled", true);
        Object previousThreshold = storage.put("messageBufferMaxBytes", 0L);
        var processors = new ArrayList<>(Factories.getStorageProcessors());
        AtomicBoolean processed = new AtomicBoolean();
        String first = "From: first@origin.test\r\n\r\n";
        String last = "From: second@other.test\r\r\nFrom: third@elsewhere.test\r\n\r\nBody";
        ConnectionMock connection = connection(first + last,
                new MessageEnvelope().addRcpt("recipient@example.test"));
        try {
            Factories.setStorageProcessors(java.util.List.of(() -> (conn, parser) -> {
                processed.set(true);
                return true;
            }));
            assertTrue(new ServerData().process(connection, new Verb("BDAT " + first.length())));
            assertFalse(new ServerData().process(connection, new Verb("BDAT " + last.length() + " LAST")));
            assertFalse(processed.get());
            assertTrue(connection.getOutput().contains("554 5.6.0"));
            assertFalse(connection.getOutput().contains("451 "));
        } finally {
            connection.getSession().close();
            connection.close();
            Factories.setStorageProcessors(processors);
            storage.put("path", previousPath);
            storage.put("enabled", previousEnabled);
            if (previousThreshold == null) storage.remove("messageBufferMaxBytes");
            else storage.put("messageBufferMaxBytes", previousThreshold);
        }
    }

    private static ConnectionMock connection(String message, MessageEnvelope envelope) {
        ConnectionMock connection = new ConnectionMock(new StringBuilder(message));
        connection.setSocket(new Socket());
        connection.getSession().addEnvelope(envelope);
        return connection;
    }
}
