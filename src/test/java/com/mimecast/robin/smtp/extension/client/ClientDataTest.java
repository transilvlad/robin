package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.naming.ConfigurationException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientDataTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedLmtpFinalReadCopiesKnownDeliveryAndMarksEveryRemainingRecipientUnknown(boolean bdat) throws Exception {
        var connection = new ConnectionMock(new StringBuilder((bdat ? "" : "354 Go\r\n") + "250 First delivered\r\n")) {
            private int finalReads;
            @Override public String read(String code) throws IOException {
                if (code.equals("250") && ++finalReads == 2) throw new IOException("Synthetic final-reply interruption");
                return super.read(code);
            }
        };
        connection.getSession().setLhlo("robin.test").setEhloBdat(bdat);
        var recipients = java.util.List.of("first@example.com", "second@example.com", "third@example.com");
        connection.getSession().addEnvelope(new MessageEnvelope().setRcpts(recipients).setChunkSize(128));
        var tx = new EnvelopeTransactionList();
        for (String recipient : recipients) tx.addTransaction("RCPT", "RCPT TO:<" + recipient + ">", "250 Admitted");
        connection.getSession().getSessionTransactionList().addEnvelope(tx);
        boolean[] closed = {false};
        var stream = new ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        };
        var client = new ClientData() {
            @Override protected InputStream getStream(com.mimecast.robin.smtp.connection.Connection ignored, boolean binary) {
                return stream;
            }
        };
        assertThrows(IOException.class, () -> client.process(connection));
        assertTrue(closed[0]);
        assertEquals(3, tx.getLmtp().size());
        assertFalse(tx.getLmtp().getFirst().isError());
        assertEquals(java.util.List.of("second@example.com", "third@example.com"), tx.getFailedRecipients());
        assertEquals("", tx.getLmtp().getLast().getResponse());
        assertTrue((bdat ? tx.getBdat().getLast() : tx.getData()).isError());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lmtpFinalResponsesCoverOnlyAdmittedRecipientsAndDrainFailuresBeforeNextCommand(boolean bdat) throws Exception {
        var connection = new ConnectionMock(new StringBuilder((bdat ? "" : "354 Go\r\n")
                + "550 5.1.1 First failed\r\n250 Second delivered\r\n221 Next command\r\n"));
        connection.getSession().setLhlo("robin.test").setEhloBdat(bdat);
        var envelope = new MessageEnvelope().setMail("sender@example.com")
                .setRcpts(java.util.List.of("denied@example.com", "first@example.com", "second@example.com"))
                .setBytes(new byte[]{1});
        envelope.setChunkSize(128);
        connection.getSession().addEnvelope(envelope);
        var tx = new EnvelopeTransactionList();
        tx.addTransaction("RCPT", "RCPT TO:<denied@example.com>", "550 Denied", true);
        tx.addTransaction("RCPT", "RCPT TO:<first@example.com>", "250 Admitted");
        tx.addTransaction("RCPT", "RCPT TO:<second@example.com>", "250 Admitted");
        connection.getSession().getSessionTransactionList().addEnvelope(tx);
        assertFalse(new ClientData().process(connection));
        assertEquals(2, tx.getLmtp().size());
        assertEquals("first@example.com", tx.getLmtp().getFirst().getAddress());
        assertTrue(tx.getLmtp().getFirst().isError());
        assertEquals("second@example.com", tx.getLmtp().getLast().getAddress());
        assertFalse(tx.getLmtp().getLast().isError());
        assertTrue(connection.read("221").startsWith("221"), "Failed final replies must not leave later recipients unread");
        assertEquals(java.util.List.of("denied@example.com", "first@example.com"), tx.getFailedRecipients());
    }

    @Test
    void lmtpUnansweredFinalResponseIsRetainedAsUnknownRecipientFailure() throws Exception {
        var connection = new ConnectionMock(new StringBuilder("354 Go\r\n250 First delivered\r\n"));
        connection.getSession().setLhlo("robin.test");
        connection.getSession().addEnvelope(new MessageEnvelope().setRcpts(java.util.List.of("first@example.com", "second@example.com")));
        var tx = new EnvelopeTransactionList();
        connection.getSession().getSessionTransactionList().addEnvelope(tx);
        assertFalse(new ClientData().process(connection));
        assertEquals(2, tx.getLmtp().size());
        assertTrue(tx.getLmtp().getLast().isError());
        assertEquals("", tx.getLmtp().getLast().getResponse());
        assertEquals(java.util.List.of("second@example.com"), tx.getFailedRecipients());
    }

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void processData() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("354 Go\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("DATA\r\n", connection.getLine(1));
        assertEquals(".\r\n", connection.getLine(2));
    }

    @Test
    void processDataWithSubjectAndMessage() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("354 Go\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        envelope.setRcpt("pepper@example.com");
        envelope.setSubject("Lost in space");
        envelope.setMessage("Rescue me!");
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("DATA\r\n", connection.getLine(1));
        assertEquals("MIME-Version: 1.0\r\n", connection.getLine(2));
        assertEquals("From: <tony@example.com>\r\n", connection.getLine(5));
        assertEquals("To: <pepper@example.com>\r\n", connection.getLine(6));
        assertEquals("Subject: Lost in space\r\n", connection.getLine(7));
        assertEquals("\r\n", connection.getLine(10));
        assertEquals("Rescue me!\r\n", connection.getLine(11));
        assertEquals(".\r\n", connection.getLine(12));
    }

    @Test
    void processDataWithFile() throws IOException {
        processDataWithOptionalFileOrStream("src/test/resources/mime/lipsum.eml", null);
    }

    @Test
    void processDataWithStream() throws IOException {
        processDataWithOptionalFileOrStream(
                null,
                new BufferedInputStream(new FileInputStream("src/test/resources/mime/lipsum.eml"), 8192)
        );
    }

    void processDataWithOptionalFileOrStream(String file, InputStream stream) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("354 Go\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setFile(file);
        envelope.setStream(stream);
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("DATA\r\n", connection.getLine(1));
        assertEquals("MIME-Version: 1.0\r\n", connection.getLine(2));
        assertEquals("Subject: Lipsum\r\n", connection.getLine(7));
        assertEquals("Content-Type: multipart/alternative;\r\n", connection.getLine(8));
        assertEquals("Lorem ipsum dolor sit amet, consectetur adipiscing elit.\r\n", connection.getLine(15));
        assertEquals("Donec vitae dapibus lacus.\r\n", connection.getLine(26));
        assertEquals("--MCBoundary11505141140170031\r\n", connection.getLine(30));
        assertEquals("<title>Lorem Ipsum</title>\r\n", connection.getLine(40));
        assertEquals("<br>Integer at finibus orci.\r\n", connection.getLine(51));
        assertEquals("--MCBoundary11505141140170031--\r\n", connection.getLine(66));    }

    @Test
    void processBdat() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setEhloBdat(true);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setChunkSize(100);
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertFalse(process);
    }

    @Test
    void processBdatWithSubjectAndMessage() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setEhloBdat(true);

        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("tony@example.com");
        envelope.setRcpt("pepper@example.com");
        envelope.setSubject("Lost in space");
        envelope.setMessage("Rescue me!");
        envelope.setChunkSize(512);
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertTrue(process);

        connection.parseLines();
        assertEquals("BDAT 277\r\n", connection.getLine(1));
        assertEquals("MIME-Version: 1.0\r\n", connection.getLine(2));
        assertEquals("From: <tony@example.com>\r\n", connection.getLine(5));
        assertEquals("To: <pepper@example.com>\r\n", connection.getLine(6));
        assertEquals("Subject: Lost in space\r\n", connection.getLine(7));
        assertEquals("\r\n", connection.getLine(10));
        assertEquals("BDAT 12 LAST\r\n", connection.getLine(11));
        assertEquals("Rescue me!\r\n", connection.getLine(12));
    }

    @Test
    void processBdatWithFile() throws IOException {
        processBdatWithFileOrStreamAndOptionalChunk(false, false);
    }

    @Test
    void processBdatWithFileAndChunkBdat() throws IOException {
        processBdatWithFileOrStreamAndOptionalChunk(true, false);
    }

    @Test
    void processBdatWithStream() throws IOException {
        processBdatWithFileOrStreamAndOptionalChunk(false, true);
    }

    @Test
    void processBdatWithStreamAndChunkBdat() throws IOException {
        processBdatWithFileOrStreamAndOptionalChunk(true, true);
    }

    void processBdatWithFileOrStreamAndOptionalChunk(boolean chunkBdat, boolean stream) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        stringBuilder.append("250 OK\r\n");
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setEhloBdat(true);

        MessageEnvelope envelope = new MessageEnvelope();
        if (stream) {
            envelope.setFile("src/test/resources/mime/lipsum.eml");
        } else {
            envelope.setStream(new BufferedInputStream(new FileInputStream("src/test/resources/mime/lipsum.eml"), 8192));
        }
        envelope.setChunkSize(512);
        envelope.setChunkBdat(chunkBdat);
        connection.getSession().addEnvelope(envelope);

        EnvelopeTransactionList transactionList = new EnvelopeTransactionList();
        transactionList.addTransaction("MAIL", "MAIL FROM:<tony@example.com>", "250 OK", false);
        connection.getSession().getSessionTransactionList().addEnvelope(transactionList);

        ClientData data = new ClientData();
        boolean process = data.process(connection);

        assertFalse(process);

        connection.parseLines();
        assertEquals("BDAT 512\r\n", connection.getLine(1));
        assertEquals("MIME-Version: 1.0\r\n", connection.getLine(2));
        assertEquals("Subject: Lipsum\r\n", connection.getLine(7));
        assertEquals("Content-Type: multipart/alternative;\r\n", connection.getLine(8));
        assertEquals("Lorem ipsum dolor sit amet, consectetur adipiscing elit.\r\n", connection.getLine(15));
        assertEquals("Vestibulum nunc massa, porttitor vel jusBDAT 512\r\n", connection.getLine(18));
        assertEquals("--MCBoundary11505141140170031\r\n", connection.getLine(32));
        assertEquals("<br>Vestibulum nunc massBDAT 512\r\n", connection.getLine(55));
        assertEquals("--MCBoundary11505141140170031--\r\n", connection.getLine(71));
    }
}
