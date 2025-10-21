package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.config.server.WebhookConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import com.mimecast.robin.smtp.verb.BdatVerb;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.smtp.webhook.WebhookCaller;
import com.mimecast.robin.storage.StorageClient;
import org.apache.commons.io.output.CountingOutputStream;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * DATA extension processor.
 */
public class ServerData extends ServerProcessor {

    /**
     * Number of MIME bytes received.
     */
    protected long bytesReceived = 0L;

    /**
     * CHUNKING advert.
     *
     * @return Advert string.
     */
    @Override
    public String getAdvert() {
        return Config.getServer().isStartTls() ? "CHUNKING" : "";
    }

    /**
     * DATA processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        if (verb.getKey().equals("bdat")) {
            binary();
            log.debug("Received: {} bytes", bytesReceived);

        } else if (verb.getKey().equals("data")) {
            ascii();
            log.debug("Received: {} bytes", bytesReceived);
        }

        // Track successful email receipt.
        SmtpMetrics.incrementEmailReceiptSuccess();

        return true;
    }

    /**
     * ASCII receipt with extended timeout.
     *
     * @throws IOException Unable to communicate.
     */
    private void ascii() throws IOException {
        if (connection.getSession().getEnvelopes().isEmpty() || connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty()) {
            connection.write(String.format(SmtpResponses.NO_VALID_RECIPIENTS_554, connection.getSession().getUID()));
            return;
        }

        // Read email lines and store to disk.
        StorageClient storageClient = asciiRead("eml");

        // Call RAW webhook after successful storage.
        callRawWebhook();

        Optional<ScenarioConfig> opt = connection.getScenario();
        if (opt.isPresent() && opt.get().getData() != null) {
            connection.write(opt.get().getData() + " [" + connection.getSession().getUID() + "]");
        } else {
            connection.write(String.format(SmtpResponses.RECEIVED_OK_250, connection.getSession().getUID()));
        }
    }

    /**
     * ASCII read.
     *
     * @param extension File extension.
     * @return StorageClient StorageClient instance.
     * @throws IOException Unable to communicate.
     */
    protected StorageClient asciiRead(String extension) throws IOException {
        connection.write(SmtpResponses.READY_WILLING_354);

        StorageClient storageClient = Factories.getStorageClient(connection, extension);

        try (CountingOutputStream cos = new CountingOutputStream(storageClient.getStream())) {
            connection.setTimeout(connection.getSession().getExtendedTimeout());
            connection.readMultiline(cos);
            bytesReceived = cos.getByteCount();

        } finally {
            connection.setTimeout(connection.getSession().getTimeout());
        }

        storageClient.save();

        return storageClient;
    }

    /**
     * Binary receipt.
     *
     * @throws IOException Unable to communicate.
     */
    private void binary() throws IOException {
        BdatVerb bdatVerb = new BdatVerb(verb);

        if (verb.getCount() == 1) {
            connection.write(SmtpResponses.INVALID_ARGS_501);
        } else {
            // Read bytes.
            StorageClient storageClient = Factories.getStorageClient(connection, "eml");
            CountingOutputStream cos = new CountingOutputStream(storageClient.getStream());

            binaryRead(bdatVerb, cos);
            bytesReceived = cos.getByteCount();

            if (bdatVerb.isLast()) {
                log.debug("Last chunk received.");
                storageClient.save();
            }

            // Call RAW webhook after successful storage.
            callRawWebhook();

            // Scenario response or accept.
            scenarioResponse(connection.getSession().getUID());
        }
    }

    /**
     * Binary read with extended timeout.
     *
     * @param verb Verb instance.
     * @param cos  CountingOutputStream instance.
     * @throws IOException Unable to communicate.
     */
    protected void binaryRead(BdatVerb verb, CountingOutputStream cos) throws IOException {
        try {
            connection.setTimeout(connection.getSession().getExtendedTimeout());
            connection.readBytes(verb.getSize(), cos);

        } finally {
            connection.setTimeout(connection.getSession().getTimeout());
            log.info("<< BYTES {}", cos.getByteCount());
        }
    }

    /**
     * Scenario response.
     *
     * @param uid UID.
     * @throws IOException Unable to communicate.
     */
    private void scenarioResponse(String uid) throws IOException {
        Optional<ScenarioConfig> opt = connection.getScenario();
        if (opt.isPresent() && opt.get().getData() != null) {
            connection.write(opt.get().getData() + " [" + uid + "]");
        }

        // Accept all.
        else {
            connection.write(String.format(SmtpResponses.CHUNK_OK_250, uid));
        }
    }

    /**
     * Calls RAW webhook if configured.
     */
    private void callRawWebhook() {
        try {
            Map<String, WebhookConfig> webhooks = Config.getServer().getWebhooks();

            String filePath = connection.getSession().getEnvelopes().isEmpty() ? null :
                    connection.getSession().getEnvelopes().getLast().getFile();
            if (filePath == null || filePath.isEmpty()) {
                return;
            }

            if (webhooks.containsKey("raw")) {
                WebhookConfig rawCfg = webhooks.get("raw");
                if (rawCfg.isEnabled()) {
                    log.debug("Calling RAW webhook with file: {}", filePath);
                    WebhookCaller.callRaw(rawCfg, filePath, connection);
                }
            }
        } catch (Exception e) {
            log.error("Error calling RAW webhook: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets bytes received.
     *
     * @return Integer.
     */
    public long getBytesReceived() {
        return bytesReceived;
    }
}
