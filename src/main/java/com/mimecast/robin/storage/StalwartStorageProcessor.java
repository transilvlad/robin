package com.mimecast.robin.storage;

import com.mimecast.robin.auth.SqlAuthManager;
import com.mimecast.robin.config.StalwartConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.sasl.SqlUserLookup;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Delivers inbound local mail directly into Stalwart using its native JMAP ingest path.
 */
public class StalwartStorageProcessor extends AbstractStorageProcessor {
    private static final Logger log = LogManager.getLogger(StalwartStorageProcessor.class);

    private final StalwartDirectDelivery directDelivery;

    public StalwartStorageProcessor() {
        this(new StalwartDirectDelivery());
    }

    StalwartStorageProcessor(StalwartDirectDelivery directDelivery) {
        this.directDelivery = directDelivery;
    }

    @Override
    protected boolean processInternal(Connection connection, EmailParser emailParser) throws IOException {
        ServerConfig serverConfig = Config.getServer();
        StalwartConfig stalwartConfig = serverConfig.getStalwart();
        if (!stalwartConfig.isEnabled()) {
            return true;
        }

        if (connection.getSession().isOutbound()) {
            log.debug("Skipping Stalwart direct ingest for outbound message uid={}", connection.getSession().getUID());
            return true;
        }

        if (connection.getSession().getEnvelopes().isEmpty()) {
            log.warn("No envelopes present for Stalwart storage processing (session UID: {}). Skipping.", connection.getSession().getUID());
            return true;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        List<String> recipients = filterAndResolveRecipients(envelope);
        if (recipients.isEmpty()) {
            log.debug("All recipients are bot addresses, skipping Stalwart direct ingest.");
            return true;
        }

        if (!stalwartConfig.isInline()) {
            enqueueDelivery(connection, envelope, recipients);
            return true;
        }

        MessageEnvelope directEnvelope = envelope.clone();
        directEnvelope.setRcpt(null);
        directEnvelope.setRcpts(new ArrayList<>(recipients));
        connection.getSession().getEnvelopes().clear();
        connection.getSession().addEnvelope(directEnvelope);

        boolean deliverySucceeded = directDelivery.deliver(connection.getSession(), 1, 0);
        EnvelopeTransactionList transactionList = connection.getSession().getSessionTransactionList().getEnvelopes().isEmpty()
                ? null : connection.getSession().getSessionTransactionList().getEnvelopes().getLast();

        if (deliverySucceeded && transactionList != null && transactionList.getFailedRecipients().isEmpty()) {
            log.info("Stalwart direct delivery successful for recipients={}", String.join(",", recipients));
            return true;
        }

        List<String> failedRecipients = transactionList != null ? transactionList.getFailedRecipients() : recipients;
        if (failedRecipients.isEmpty()) {
            failedRecipients = recipients;
        }

        log.warn("Stalwart direct delivery failed for recipients={} uid={}",
                String.join(",", failedRecipients), connection.getSession().getUID());

        directEnvelope.setRcpts(new ArrayList<>(failedRecipients));
        for (String failedRecipient : failedRecipients) {
            processFailure(connection, serverConfig, failedRecipient);
        }

        return true;
    }

    private void enqueueDelivery(Connection connection, MessageEnvelope envelope, List<String> recipients) {
        RelaySession relaySession = new RelaySession(Factories.getSession()).setProtocol("stalwart-direct");
        relaySession.getSession().setDirection(connection.getSession().getDirection());

        MessageEnvelope queuedEnvelope = new MessageEnvelope()
                .setMail(envelope.getMail())
                .setRcpts(new ArrayList<>(recipients));
        queuedEnvelope.setFile(envelope.getFile());
        queuedEnvelope.setMessageSource(envelope.getMessageSource());

        relaySession.getSession().addEnvelope(queuedEnvelope);
        QueueFiles.persistEnvelopeFiles(relaySession);
        PersistentQueue.getInstance().enqueue(relaySession);

        log.info("Queued Stalwart direct delivery for sender={} recipients={} uid={}",
                envelope.getMail(), String.join(",", recipients), connection.getSession().getUID());
    }

    private List<String> filterAndResolveRecipients(MessageEnvelope envelope) {
        List<String> nonBotRecipients = new ArrayList<>();
        for (String recipient : envelope.getRcpts()) {
            if (!envelope.isBotAddress(recipient)) {
                nonBotRecipients.add(recipient);
            } else {
                log.debug("Skipping Stalwart direct ingest for bot address: {}", recipient);
            }
        }
        return resolveAliases(nonBotRecipients);
    }

    private List<String> resolveAliases(List<String> recipients) {
        SqlUserLookup lookup = SqlAuthManager.getUserLookup();
        if (lookup == null) {
            return recipients;
        }
        List<String> resolved = new ArrayList<>();
        for (String recipient : recipients) {
            Optional<String> alias = lookup.resolveAlias(recipient);
            if (alias.isPresent()) {
                log.debug("Resolved alias {} -> {}", recipient, alias.get());
                resolved.add(alias.get());
            } else {
                resolved.add(recipient);
            }
        }
        return resolved;
    }

    protected void processFailure(Connection connection, ServerConfig config, String mailbox) {
        String sender = connection.getSession().getEnvelopes().getLast().getMail();
        RelaySession relaySession = new RelaySession(Factories.getSession()).setProtocol("esmtp");
        MessageEnvelope envelope = new MessageEnvelope();
        relaySession.getSession().addEnvelope(envelope);
        StalwartConfig stalwartConfig = config.getStalwart();

        if (stalwartConfig.getFailureBehaviour().equalsIgnoreCase("bounce")) {
            BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession().clone()), mailbox);
            envelope.setMail("mailer-daemon@" + config.getHostname())
                    .setRcpt(sender)
                    .setBytes(bounce.getStream().toByteArray());
            log.info("Bouncing rejected Stalwart mailbox='{}' sender='{}' uid={}", mailbox, sender, connection.getSession().getUID());
        } else {
            envelope.setFile(connection.getSession().getEnvelopes().getLast().getFile());
            envelope.setMail(sender);
            envelope.setRcpts(new ArrayList<>(List.of(mailbox)));
            relaySession.getSession().setDirection(connection.getSession().getDirection());
            relaySession.setProtocol("stalwart-direct");
            QueueFiles.persistEnvelopeFiles(relaySession);
        }

        PersistentQueue.getInstance().enqueue(relaySession);
    }
}
