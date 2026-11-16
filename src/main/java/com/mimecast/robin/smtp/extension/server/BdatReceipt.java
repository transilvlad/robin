package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.smtp.PendingReceipt;
import com.mimecast.robin.storage.StorageClient;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * One message received as several BDAT chunks, written to a single storage receipt.
 * <p>Opened by the first chunk, appended to by each following chunk and saved after BDAT LAST.
 */
final class BdatReceipt implements PendingReceipt {
    private static final Logger log = LogManager.getLogger(BdatReceipt.class);

    private final StorageClient storageClient;
    private final CountingOutputStream stream;

    BdatReceipt(StorageClient storageClient, CountingOutputStream stream) {
        this.storageClient = storageClient;
        this.stream = stream;
    }

    /**
     * A receipt that counts the message without storing it, for blackholed messages.
     */
    static BdatReceipt discarding(CountingOutputStream stream) {
        return new BdatReceipt(null, stream);
    }

    StorageClient storageClient() {
        return storageClient;
    }

    CountingOutputStream stream() {
        return stream;
    }

    /**
     * Message bytes received so far, excluding the locally added Received header.
     */
    long bytes() {
        return stream.getByteCount();
    }

    /**
     * Closes the stream once the last chunk is written, before the receipt is saved.
     */
    void finish() throws IOException {
        stream.close();
    }

    @Override
    public void abort() {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Unable to close abandoned BDAT receipt: {}", e.getMessage());
        }
        try {
            if (storageClient != null) storageClient.abort();
        } catch (IOException e) {
            log.error("Unable to discard abandoned BDAT receipt: {}", e.getMessage());
        }
    }
}
