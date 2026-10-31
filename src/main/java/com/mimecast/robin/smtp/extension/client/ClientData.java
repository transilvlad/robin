package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.config.client.LoggingConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.io.ChunkedInputStream;
import com.mimecast.robin.smtp.io.MagicInputStream;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.StreamUtils;
import org.apache.commons.io.input.BoundedInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DATA extension processor.
 */
public class ClientData extends ClientProcessor {
    private static final int FILE_BUFFER_SIZE = 8192;

    /**
     * MessageEnvelope instance.
     */
    protected int messageID;

    /**
     * MessageEnvelope instance.
     */
    protected MessageEnvelope envelope;

    /**
     * EnvelopeTransactionList instance.
     */
    protected EnvelopeTransactionList envelopeTransactions;

    /**
     * DATA processor.
     *
     * @param connection Connection instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection) throws IOException {
        super.process(connection);

        // Select message to send.
        messageID = connection.getSession().getSessionTransactionList().getEnvelopes().size() - 1; // Adjust as it's initially added in ClientMail.

        // Select message envelope and transactions.
        envelope = connection.getSession().getEnvelopes().get(messageID);
        envelopeTransactions = connection.getSession().getSessionTransactionList().getEnvelopes().get(messageID);

        // Evaluate is BDAT enabled.
        boolean bdat = connection.getSession().isEhloBdat() && envelope.getChunkSize() >= 128;

        // Get data stream.
        InputStream inputStream = getStream(connection, bdat);

        try {
            return bdat ? processBdat(inputStream) : processData("DATA", inputStream);
        } finally {
            StreamUtils.closeQuietly(inputStream);
        }
    }

    /**
     * DATA stream selector.
     *
     * @param connection Connection instance.
     * @param bdat       Is binary mode enabled.
     * @return InputStream instance.
     * @throws IOException Unable to communicate.
     */
    protected InputStream getStream(Connection connection, boolean bdat) throws IOException {
        InputStream inputStream = null;

        if (envelope.getMime() != null && !envelope.getMime().isEmpty()) {
            log.debug("Sending email from MIME.");

            Path path = Files.createTempFile("robin-", ".eml");
            try (Closeable ignored = () -> Files.delete(path)) {
                Magic.putTransactionMagic(messageID, connection.getSession()); // Put magic early for EmailBuilder use.
                try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(path.toFile()), FILE_BUFFER_SIZE)) {
                    new EmailBuilder(connection.getSession(), envelope)
                            .setLogTextPartsBody((new LoggingConfig(Config.getProperties().getMapProperty("logging"))
                                    .getBooleanProperty("textPartBody", false)))
                            .buildMime()
                            .writeTo(outputStream);
                }

                inputStream = new BufferedInputStream(new FileInputStream(path.toFile()), FILE_BUFFER_SIZE);
            }

        } else if (envelope.hasMaterializedFile()) {
            log.debug("Sending email from file: {}", envelope.getFile());
            inputStream = new BufferedInputStream(new FileInputStream(envelope.getFile()), FILE_BUFFER_SIZE);

        } else if (envelope.getFolder() != null) {
            String file = envelope.getFolderFile();
            log.debug("Sending email from file: {}", file);
            inputStream = new BufferedInputStream(new FileInputStream(file), FILE_BUFFER_SIZE);

        } else if (envelope.hasMessageSource()) {
            log.debug("Sending email from envelope source.");
            inputStream = envelope.openMessageStream();

        } else if (envelope.getMessage() != null && !bdat) {
            log.debug("Sending email from headers and body.");
            inputStream = new ByteArrayInputStream((envelope.buildHeaders() + "\r\n" + envelope.getMessage()).getBytes());
        }

        if (envelope.isPrependHeaders()) {
            Map<String, String> headers = envelope.getHeaders();
            if (!headers.isEmpty()) {
                List<String> prependHeaders = new ArrayList<>();
                headers.forEach((name, value) -> prependHeaders.add(name + ": " + value + "\r\n"));
                inputStream = new SequenceInputStream(Collections.enumeration(Arrays.asList(new ByteArrayInputStream(String.join("", prependHeaders).getBytes()), inputStream)));
            }
        }

        return inputStream;
    }

    /**
     * DATA processor.
     *
     * @param verb        Verb.
     * @param inputStream InputStream instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @SuppressWarnings("SameParameterValue")
    protected boolean processData(String verb, InputStream inputStream) throws IOException {
        String write = verb != null ? verb : "DATA";
        connection.write(write);

        String read;
        read = connection.read("354");
        if (!read.startsWith("354")) {
            envelopeTransactions.addTransaction(write, write, read, true);
            return false;
        }

        // Send data
        if (inputStream != null) {
            if (envelope.getTerminateAfterBytes() > 0) {
                log.debug("Terminating after {} bytes.", envelope.getTerminateAfterBytes());
                envelope.setTerminateBeforeDot(true);
                inputStream = BoundedInputStream.builder()
                        .setInputStream(inputStream)
                        .setMaxCount(envelope.getTerminateAfterBytes())
                        .get();
            }

            connection.stream(
                    new MagicInputStream(inputStream, envelope),
                    envelope.getSlowBytes(),
                    envelope.getSlowWait()
            );
        }

        // Terminate before dot.
        if (envelope.isTerminateBeforeDot()) {
            log.debug("Terminating before <CRLF>.<CRLF>");
            connection.close();
            return false;
        }

        log.debug("Sending [CRLF].[CRLF]");
        connection.write(".");

        // Terminate after dot.
        if (envelope.isTerminateAfterDot()) {
            log.debug("Terminating after <CRLF>.<CRLF>");
            connection.close();
            return false;
        }

        return readFinalResponses(write, write);
    }

    private boolean readFinalResponses(String command, String payload) throws IOException {
        boolean lmtp = connection.getSession().getLhlo() != null && !connection.getSession().getLhlo().isEmpty();
        if (!lmtp) {
            String response = connection.read("250");
            envelopeTransactions.addTransaction(command, payload, response, !response.startsWith("250"));
            return response.startsWith("250");
        }
        var admissions = envelopeTransactions.getRcpt();
        List<String> accepted = admissions.isEmpty() ? envelope.getRcpts() : admissions.stream()
                .filter(transaction -> !transaction.isError() && transaction.getResponseCode().startsWith("2"))
                .map(com.mimecast.robin.smtp.transaction.Transaction::getAddress).toList();
        if (accepted.isEmpty()) {
            envelopeTransactions.addTransaction(command, payload, "", true);
            return false;
        }
        boolean success = true;
        StringBuilder responses = new StringBuilder();
        for (int i = 0; i < accepted.size(); i++) {
            String recipient = accepted.get(i);
            String response;
            try {
                response = connection.read("250");
            } catch (IOException e) {
                for (int remaining = i; remaining < accepted.size(); remaining++) {
                    envelopeTransactions.addTransaction("LMTP", "RCPT TO:<" + accepted.get(remaining) + ">", "", true);
                }
                envelopeTransactions.addTransaction(command, payload, responses.toString(), true);
                throw e;
            }
            boolean delivered = response.startsWith("2");
            envelopeTransactions.addTransaction("LMTP", "RCPT TO:<" + recipient + ">", response, !delivered);
            responses.append(response);
            success &= delivered;
        }
        envelopeTransactions.addTransaction(command, payload, responses.toString(), !success);
        return success;
    }

    /**
     * BDAT processor.
     *
     * @param inputStream InputStream instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    private boolean processBdat(InputStream inputStream) throws IOException {
        boolean accepted = false;

        if (inputStream != null) {
            try (ChunkedInputStream chunks = new ChunkedInputStream(inputStream, envelope)) {
                ByteArrayOutputStream chunk;
                while (chunks.hasChunks()) {
                    chunk = chunks.getChunk();
                    accepted = writeChunk(chunk.toByteArray(), !chunks.hasChunks());
                    if (!accepted) return false;
                }
            }

        } else if (envelope.getMessage() != null) {
            // Write headers
            accepted = writeChunk((envelope.buildHeaders() + "\r\n").getBytes(), false);
            if (!accepted) return false;

            // Write body
            accepted = writeChunk((envelope.getMessage() + "\r\n").getBytes(), true);
        }

        return accepted;
    }

    /**
     * Writes BDAT chunk to socket.
     *
     * @param chunk Chunk to write as byte array.
     * @param last  Is last chunk?
     * @return Whether all required replies confirmed acceptance.
     * @throws IOException Unable to communicate.
     */
    private boolean writeChunk(byte[] chunk, boolean last) throws IOException {
        byte[] bdat = ("BDAT " + chunk.length + (last ? " LAST" : "") + "\r\n").getBytes();
        byte[] payload;

        // Merge bdat to first chunk.
        if (envelope.isChunkBdat()) {
            payload = new byte[bdat.length + chunk.length];
            System.arraycopy(bdat, 0, payload, 0, bdat.length);
            System.arraycopy(chunk, 0, payload, bdat.length, chunk.length);

        } else {
            connection.write(bdat);
            payload = chunk;
        }

        connection.write(payload, envelope.isChunkWrite(), envelope.getSlowBytes(), envelope.getSlowWait());

        if (last) return readFinalResponses("BDAT", new String(bdat));
        String read = connection.read("250");
        envelopeTransactions.addTransaction("BDAT", new String(bdat), read, !read.startsWith("250"));

        return read.startsWith("250");
    }
}
