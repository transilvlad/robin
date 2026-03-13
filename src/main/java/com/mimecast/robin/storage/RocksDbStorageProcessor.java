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
import java.util.LinkedHashMap;
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
        if (!envelope.hasMessageSource()) {
            log.error("No message source available for RocksDB storage processing");
            return false;
        }

        RocksDbMailboxStore store = RocksDbMailboxStoreManager.getConfiguredStore();
        byte[] sourceBytes = envelope.readMessageBytes();
        Map<String, String> headers = readHeaders(emailParser);
        if (connection.getSession().isOutbound()) {
            if (envelope.getMail() == null || envelope.getMail().isBlank()) {
                log.warn("Skipping outbound RocksDB storage because MAIL FROM is empty");
                return true;
            }
            byte[] content = buildStoredMessage(connection, sourceBytes, null);
            store.storeOutbound(envelope.getMail(), content, envelope.getFile(), headers);
            return true;
        }

        for (String recipient : envelope.getRcpts()) {
            if (envelope.isBotAddress(recipient)) {
                continue;
            }
            byte[] content = buildStoredMessage(connection, sourceBytes, recipient);
            store.storeInbound(recipient, content, envelope.getFile(), headers);
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

    private byte[] buildStoredMessage(Connection connection, byte[] sourceBytes, String recipient) {
        ReceivedHeader receivedHeader = new ReceivedHeader(connection);
        if (recipient != null && !recipient.isBlank()) {
            receivedHeader.setRecipientAddress(recipient);
        }
        byte[] headerBytes = receivedHeader.toString().getBytes();
        byte[] content = new byte[headerBytes.length + sourceBytes.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(sourceBytes, 0, content, headerBytes.length, sourceBytes.length);
        return content;
    }
}
