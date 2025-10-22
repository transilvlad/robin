package com.mimecast.robin.queue.bounce;

import com.mimecast.robin.config.client.LoggingConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Bounce message generator.
 */
public class BounceMessageGenerator {
    private static final Logger log = LogManager.getLogger(BounceMessageGenerator.class);

    private final RelaySession relaySession;
    private final String recipient;

    /**
     * Constructs a new instance of BounceMessageGenerator with given RelaySession instance and recipient.
     *
     * @param relaySession RelaySession instance.
     * @param recipient Recipient email address.
     */
    public BounceMessageGenerator(RelaySession relaySession, String recipient) {
        this.relaySession = relaySession;
        this.recipient = recipient;
    }

    /**
     * Generates the complete bounce message as a ByteArrayOutputStream.
     *
     * @return ByteArrayOutputStream containing the bounce message.
     */
    public ByteArrayOutputStream getStream() {
        BounceGenerator bounceGenerator = new BounceGenerator(relaySession);
        String text = bounceGenerator.generatePlainText(recipient);
        String status = bounceGenerator.generateDeliveryStatus(recipient);

        // Build MIME.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            new EmailBuilder(relaySession.getSession(), new MessageEnvelope())
                    .setLogTextPartsBody((new LoggingConfig(Config.getProperties().getMapProperty("logging"))
                            .getBooleanProperty("textPartBody", false)))
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

        return stream;
    }
}
