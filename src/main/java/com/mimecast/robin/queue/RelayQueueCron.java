package com.mimecast.robin.queue;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.queue.bounce.BounceGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RelayQueue queue cron job.
 */
public class RelayQueueCron {
    private static final Logger log = LogManager.getLogger(RelayQueueCron.class);

    /**
     * Main method to start the cron job.
     */
    public static void run() {
        RelayQueue queue = new RelayQueue();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable task = () -> {
            RelaySession relaySession = queue.dequeue();
            if (relaySession != null) {
                // Not yet time to retry, re-enqueue and return.
                int nextRetrySeconds = RetryScheduler.getNextRetry(relaySession.getRetryCount());
                if (relaySession.getLastRetryTime() < System.currentTimeMillis() / 1000L + nextRetrySeconds) {
                    new RelayQueue().enqueue(relaySession);
                    return;
                }

                // Clear transaction list and try again.
                relaySession.getSession().getSessionTransactionList().clear();

                if (relaySession.getProtocol().equalsIgnoreCase("dovecot-lda")) {
                    new DovecotLdaDelivery(relaySession).send();
                } else {
                    new EmailDelivery(relaySession.getSession()).send();
                }

                // If there are still errors bump the retry count and re-enqueue.
                if (!relaySession.getSession().getSessionTransactionList().getErrors().isEmpty()) {
                    if (relaySession.getRetryCount() < 30) {
                        relaySession.bumpRetryCount();
                        new RelayQueue().enqueue(relaySession);
                    } else {
                        // Generate bounce for each recipient.
                        for (String recipient : relaySession.getSession().getEnvelopes().getLast().getRcpts()) {
                            BounceGenerator bounceGenerator = new BounceGenerator(relaySession);
                            String text = bounceGenerator.generatePlainText(recipient);
                            String status = bounceGenerator.generateDeliveryStatus(recipient);

                            // Build MIME.
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            try {
                                new EmailBuilder(relaySession.getSession(), new MessageEnvelope())
                                        .addHeader("Subject", "Delivery Status Notification (Failure)")
                                        .addHeader("To", recipient)
                                        .addHeader("From", "Mail Delivery Subsystem <mailer-daemon@" + Config.getServer().getHostname() + ">")

                                        .addPart(new TextMimePart(text.getBytes())
                                                .addHeader("Content-Type", "text/plain; charset=\"UTF-8\"")
                                                .addHeader("Content-Transfer-Encoding", "7bit")
                                        )

                                        .addPart(new TextMimePart(status.getBytes())
                                                .addHeader("Content-Type", "message/delivery-status; charset=\"UTF-8\"")
                                                .addHeader("Content-Transfer-Encoding", "7bit")
                                        )
                                        .writeTo(stream);
                            } catch (IOException e) {
                                log.error("Failed to build bounce message for: {} due to error: {}", recipient, e.getMessage());
                            }

                            RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                                    .setProtocol("esmtp");

                            MessageEnvelope envelope = new MessageEnvelope()
                                    .setMail("mailer-daemon@" + Config.getServer().getHostname())
                                    .setRcpt(recipient)
                                    .setStream(new ByteArrayInputStream(stream.toByteArray()));

                            relaySessionBounce.getSession().addEnvelope(envelope);
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
