package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;

/**
 * Dovecot storage processor for mailbox storage.
 */
public class DovecotStorageProcessor implements StorageProcessor {
    private static final Logger log = LogManager.getLogger(DovecotStorageProcessor.class);

    /**
     * Processes the email for mailbox storage.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return True if the email is not spam, false if spam is detected.
     * @throws IOException If an I/O error occurs during processing.
     */
    @Override
    public boolean process(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig config = Config.getServer();
        if (config.getDovecot().getBooleanProperty("saveToDovecotLda")) {
            getDovecotLdaDeliveryInstance(connection, config.getDovecot()).send();

            // If there are multiple recipients and one fails bounce recipient instead of throwing an exception.
            EnvelopeTransactionList envelopeTransactionList = connection.getSession().getSessionTransactionList().getEnvelopes().getLast();
            if (!envelopeTransactionList.getErrors().isEmpty()) {
                if (envelopeTransactionList.getRecipients() != envelopeTransactionList.getFailedRecipients()) {
                    connection.getSession().getEnvelopes().getLast().setRcpts(connection.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients());

                    for (String recipient : connection.getSession().getEnvelopes().getLast().getRcpts()) {
                        // Generate bounce email.
                        BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession()), recipient);

                        // Build the session.
                        RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                                .setProtocol("esmtp");

                        // Create the envelope.
                        MessageEnvelope envelope = new MessageEnvelope()
                                .setMail("mailer-daemon@" + config.getHostname())
                                .setRcpt(recipient)
                                .setBytes(bounce.getStream().toByteArray());
                        relaySessionBounce.getSession().addEnvelope(envelope);

                        // Queue bounce for delivery using runtime-configured queue file (fallback to default).
                        File queueFile = new File(config.getQueue().getStringProperty(
                                "queueFile",
                                RelayQueueCron.QUEUE_FILE.getAbsolutePath()
                        ));

                        // Persist any envelope files (no-op for bytes-only envelopes) before enqueue.
                        QueueFiles.persistEnvelopeFiles(relaySessionBounce);

                        PersistentQueue.getInstance(queueFile)
                                .enqueue(relaySessionBounce);
                    }
                } else {
                    throw new IOException("Storage unable to save to Dovecot LDA");
                }
            }
        }

        return true;
    }

    /**
     * Get DovecotLdaDelivery instance.
     * <p>Can be overridden for testing/mocking purposes.
     *
     * @param connection    Connection instance.
     * @param dovecotConfig Dovecot configuration.`
     * @return DovecotLdaDelivery instance.
     */
    protected DovecotLdaDelivery getDovecotLdaDeliveryInstance(Connection connection, BasicConfig dovecotConfig) {
        RelaySession relaySession = new RelaySession(connection.getSession());
        if (connection.getSession().isOutbound()) {
            relaySession.setMailbox(dovecotConfig.getStringProperty("outboundMailbox", "Sent"));
        }

        return new DovecotLdaDelivery(relaySession);
    }
}
