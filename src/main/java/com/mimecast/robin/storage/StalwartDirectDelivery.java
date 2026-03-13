package com.mimecast.robin.storage;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.storage.stalwart.StalwartApiClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Direct Stalwart mailbox delivery helper backed by Stalwart HTTP/JMAP ingest.
 */
public class StalwartDirectDelivery {
    private static final Logger log = LogManager.getLogger(StalwartDirectDelivery.class);

    private final StalwartApiClient client;

    public StalwartDirectDelivery() {
        this(StalwartApiClient.shared());
    }

    public StalwartDirectDelivery(StalwartApiClient client) {
        this.client = client;
    }

    public boolean deliver(Session sourceSession, long maxAttempts, int retryDelaySeconds) {
        if (sourceSession == null || sourceSession.getEnvelopes().isEmpty()) {
            return false;
        }

        sourceSession.getSessionTransactionList().clear();
        boolean allSucceeded = true;
        for (MessageEnvelope envelope : sourceSession.getEnvelopes()) {
            EnvelopeTransactionList envelopeTransactions = new EnvelopeTransactionList();
            sourceSession.getSessionTransactionList().addEnvelope(envelopeTransactions);
            allSucceeded &= deliverEnvelope(envelope, envelopeTransactions);
        }

        return allSucceeded;
    }

    private boolean deliverEnvelope(MessageEnvelope envelope, EnvelopeTransactionList envelopeTransactions) {
        String mailFrom = envelope.getMail() != null ? envelope.getMail() : "";
        envelopeTransactions.addTransaction("MAIL", "FROM:<" + mailFrom + ">", "250 2.1.0 Sender OK");

        byte[] rawMessage;
        try {
            rawMessage = readRawMessage(envelope);
        } catch (IOException e) {
            markAllRecipientsFailed(envelopeTransactions, envelope.getRcpts(), "451 4.3.0 Failed to read queued message: " + e.getMessage());
            envelopeTransactions.addTransaction("DATA", "451 4.3.0 Failed to read queued message", true);
            return false;
        }

        Map<String, String> failures;
        try {
            failures = client.deliverToRecipients(rawMessage, envelope.getRcpts());
        } catch (IOException e) {
            markAllRecipientsFailed(envelopeTransactions, envelope.getRcpts(), "451 4.4.0 Stalwart direct delivery failed: " + e.getMessage());
            envelopeTransactions.addTransaction("DATA", "451 4.4.0 Stalwart direct delivery failed", true);
            return false;
        }

        boolean hasFailures = false;
        for (String recipient : envelope.getRcpts()) {
            String failure = failures.get(recipient);
            if (failure == null) {
                envelopeTransactions.addTransaction("RCPT", "TO:<" + recipient + ">", "250 2.1.5 Recipient OK");
            } else {
                hasFailures = true;
                envelopeTransactions.addTransaction("RCPT", "TO:<" + recipient + ">", "550 5.1.1 " + failure, true);
            }
        }

        if (hasFailures) {
            envelopeTransactions.addTransaction("DATA", "250 2.0.0 Message partially imported");
            log.warn("Partial Stalwart direct delivery failure for recipients={}", String.join(",", failures.keySet()));
            return false;
        }

        envelopeTransactions.addTransaction("DATA", "250 2.0.0 Message imported via Stalwart direct ingest");
        return true;
    }

    private void markAllRecipientsFailed(EnvelopeTransactionList envelopeTransactions, List<String> recipients, String response) {
        for (String recipient : recipients) {
            envelopeTransactions.addTransaction("RCPT", "TO:<" + recipient + ">", response, true);
        }
    }

    private byte[] readRawMessage(MessageEnvelope envelope) throws IOException {
        if (envelope.hasMessageSource()) {
            return envelope.readMessageBytes();
        }
        if (envelope.getFolderFile() != null) {
            return Files.readAllBytes(Path.of(envelope.getFolderFile()));
        }
        throw new IOException("No message source available");
    }
}
