package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RelayQueue queue cron job.
 */
public class RelayQueueCron {
    private static final Logger log = LogManager.getLogger(RelayQueueCron.class);

    // Queue file from config.
    public static final File QUEUE_FILE = new File(Config.getServer().getRelay().getStringProperty("queueFile", "/tmp/robinRelayQueue.db"));

    /**
     * Main method to start the cron job.
     */
    public static void run() {
        try (PersistentQueue<RelaySession> queue = PersistentQueue.getInstance(QUEUE_FILE)) {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

            Runnable task = () -> {
                RelaySession relaySession = queue.dequeue();
                if (relaySession != null) {
                    // Not yet time to retry, re-enqueue and return.
                    int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
                    if (relaySession.getLastRetryTime() < System.currentTimeMillis() / 1000L + nextRetrySeconds) {
                        queue.enqueue(relaySession);
                        return;
                    }

                    // Clear transaction list and try again.
                    relaySession.getSession().getSessionTransactionList().clear();

                    if (relaySession.getProtocol().equalsIgnoreCase("dovecot-lda")) {
                        new DovecotLdaDelivery(relaySession).send();
                    } else {
                        new EmailDelivery(relaySession.getSession()).send();
                    }

                    // Remove successful recipients from the envelopes and successful envelopes from the session.
                    List<EnvelopeTransactionList> envelopes = relaySession.getSession().getSessionTransactionList().getEnvelopes();
                    List<MessageEnvelope> successfulEnvelopes = new ArrayList<>();
                    for (int i = 0; i < envelopes.size(); i++) {
                        EnvelopeTransactionList transactions = envelopes.get(i);
                        MessageEnvelope envelope = relaySession.getSession().getEnvelopes().get(i);

                        if (transactions.getErrors().isEmpty()) {
                            successfulEnvelopes.add(envelope);
                        } else {
                            // Some recipients succeeded, some failed. Remove successful recipients.
                            if (transactions.getRecipients() != transactions.getFailedRecipients()) {
                                envelope.setRcpts(transactions.getFailedRecipients());
                            }
                        }
                    }

                    // Remove fully successful envelopes.
                    relaySession.getSession().getEnvelopes().removeAll(successfulEnvelopes);

                    // If there are still envelopes to process, check retry count.
                    // If retry count < 30, bump and re-enqueue.
                    // If retry count >= 30, generate bounces for each recipient in each envelope.
                    if (!relaySession.getSession().getEnvelopes().isEmpty()) {
                        if (relaySession.getRetryCount() < 30) {
                            relaySession.bumpRetryCount();
                            queue.enqueue(relaySession);
                        } else {
                            for (String recipient : relaySession.getSession().getEnvelopes().getLast().getRcpts()) {
                                // Generate bounce email.
                                BounceMessageGenerator bounce = new BounceMessageGenerator(relaySession, recipient);

                                // Build the session.
                                RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                                        .setProtocol("esmtp");

                                // Create the envelope.
                                MessageEnvelope envelope = new MessageEnvelope()
                                        .setMail("mailer-daemon@" + Config.getServer().getHostname())
                                        .setRcpt(recipient)
                                        .setBytes(bounce.getStream().toByteArray());
                                relaySessionBounce.getSession().addEnvelope(envelope);

                                // Queue bounce for delivery.
                                queue.enqueue(relaySessionBounce);
                            }
                        }
                    }
                }
            };

            // Schedule the task to run every minute after a minute.
            scheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.MINUTES);

            // Add shutdown hook to close resources.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.shutdown();
                queue.close();
            }));
        }
    }
}
