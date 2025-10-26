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

import javax.naming.LimitExceededException;
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
     * Envelope limit.
     */
    private int emailSizeLimit = 10242400; // 10 MB.

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
            if (!binary()) {
                log.debug("Received: {} bytes", bytesReceived);
                return false;
            }
            log.debug("Received: {} bytes", bytesReceived);

        } else if (verb.getKey().equals("data")) {
            if (!ascii()) {
                log.debug("Received: {} bytes", bytesReceived);
                return false;
            }
        }

        // Track successful email receipt.
        SmtpMetrics.incrementEmailReceiptSuccess();

        return true;
    }

    /**
     * ASCII receipt with extended timeout.
     *
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    private boolean ascii() throws IOException {
        if (connection.getSession().getEnvelopes().isEmpty() || connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty()) {
            connection.write(String.format(SmtpResponses.NO_VALID_RECIPIENTS_554, connection.getSession().getUID()));
            return false;
        }

        // Read email lines and store to disk.
        try {
            if (!asciiRead("eml")) {
                return false;
            }
        } catch (LimitExceededException e) {
            connection.write(String.format(SmtpResponses.MESSAGE_SIZE_LIMIT_EXCEEDED_552, connection.getSession().getUID()));
        }

        // Call RAW webhook after successful storage.
        callRawWebhook();

        Optional<ScenarioConfig> opt = connection.getScenario();
        if (opt.isPresent() && opt.get().getData() != null) {
            connection.write(opt.get().getData() + " [" + connection.getSession().getUID() + "]");
        } else {
            connection.write(String.format(SmtpResponses.RECEIVED_OK_250, connection.getSession().getUID()));
        }

        return true;
    }

    /**
     * ASCII read.
     *
     * @param extension File extension.
     * @return Boolean.
     * @throws IOException            Unable to communicate.
     * @throws LimitExceededException Limit exceeded.
     */
    protected boolean asciiRead(String extension) throws IOException, LimitExceededException {
        connection.write(SmtpResponses.READY_WILLING_354);

        StorageClient storageClient = Factories.getStorageClient(connection, extension);

        try (CountingOutputStream cos = new CountingOutputStream(storageClient.getStream())) {
            connection.setTimeout(connection.getSession().getExtendedTimeout());
            connection.readMultiline(cos, emailSizeLimit);
            bytesReceived = cos.getByteCount();
        } finally {
            connection.setTimeout(connection.getSession().getTimeout());
        }

        return storageClient.save();
    }

    /**
     * Binary receipt.
     * TODO: Support multiple BDAT chunks.
     *
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    private boolean binary() throws IOException {
        BdatVerb bdatVerb = new BdatVerb(verb);

        if (verb.getCount() == 1) {
            connection.write(SmtpResponses.INVALID_ARGS_501);
        } else if (bdatVerb.getSize() > emailSizeLimit) {
            connection.write(String.format(SmtpResponses.MESSAGE_SIZE_LIMIT_EXCEEDED_552, connection.getSession().getUID()));
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

        return true;
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
     * Sets email size limit.
     *
     * @param limit Limit value.
     * @return ServerData instance.
     */
    public ServerData setEmailSizeLimit(int limit) {
        this.emailSizeLimit = limit;
        return this;
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
