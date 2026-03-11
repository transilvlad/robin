package com.mimecast.robin.storage;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.headers.ReceivedHeader;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStore;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStoreManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage processor that persists Robin mailboxes into RocksDB.
 */
public class RocksDbStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(RocksDbStorageProcessor.class);

    @Override
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        if (!RocksDbMailboxStoreManager.isEnabled()) {
            return true;
        }

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for RocksDB storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        String sourceFile = envelope.getFile();
        if (sourceFile == null || !Files.exists(Path.of(sourceFile))) {
            log.error("Source file does not exist for RocksDB storage processing: {}", sourceFile);
            return false;
        }

        RocksDbMailboxStore store = RocksDbMailboxStoreManager.getConfiguredStore();
        if (connection.getSession().isOutbound()) {
            if (envelope.getMail() == null || envelope.getMail().isBlank()) {
                log.warn("Skipping outbound RocksDB storage because MAIL FROM is empty");
                return true;
            }
            byte[] content = buildStoredMessage(connection, sourceFile, null);
            store.storeOutbound(envelope.getMail(), content, sourceFile, readHeaders(emailParser));
            return true;
        }

        for (String recipient : envelope.getRcpts()) {
            if (envelope.isBotAddress(recipient)) {
                continue;
            }
            byte[] content = buildStoredMessage(connection, sourceFile, recipient);
            store.storeInbound(recipient, content, sourceFile, readHeaders(emailParser));
        }
        return true;
    }

    private Map<String, String> readHeaders(EmailParser emailParser) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (emailParser == null || emailParser.getHeaders() == null) {
            return headers;
        }
        for (MimeHeader header : emailParser.getHeaders().get()) {
            headers.put(header.getName(), header.getValue());
        }
        return headers;
    }

    private byte[] buildStoredMessage(Connection connection, String sourceFile, String recipient) throws IOException {
        ReceivedHeader receivedHeader = new ReceivedHeader(connection);
        if (recipient != null && !recipient.isBlank()) {
            receivedHeader.setRecipientAddress(recipient);
        }
        byte[] headerBytes = receivedHeader.toString().getBytes();
        byte[] sourceBytes = Files.readAllBytes(Path.of(sourceFile));
        byte[] content = new byte[headerBytes.length + sourceBytes.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(sourceBytes, 0, content, headerBytes.length, sourceBytes.length);
        return content;
    }
}
