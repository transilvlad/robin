package com.mimecast.robin.queue.bounce;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.MessageEnvelope;

/**
 * Bounce message generator.
 */
public class BounceGenerator {

    // Relay session instance.
    private final RelaySession relaySession;

    /**
     * Constructs a new instance of BounceGenerator with given RelaySession instance.
     * @param relaySession RelaySession instance.
     */
    public BounceGenerator(RelaySession relaySession) {
        this.relaySession = relaySession;
    }

    /**
     * Generates the bounce text/plain part based on the relay session details.
     *
     * @param recipient Recipient email address.
     * @return String.
     */
    public String generatePlainText(String recipient) {
        return "The original message was received at " + relaySession.getSession().getDate() + "\r\n" +
                "from " + relaySession.getSession().getFriendRdns() + " [" + relaySession.getSession().getFriendAddr() + "]\r\n" +
                "\r\n" +
                "   ----- The following addresses had permanent fatal errors -----\r\n" +
                "From: <" + originalEnvelope(recipient).getMail() + ">\r\n" +
                "To: <" + recipient + ">\r\n" +
                "    (reason: " + relaySession.getRejection() + ")\r\n";
    }

    /**
     * Generates the bounce message/status part based on the relay session details.
     *
     * @param recipient Recipient email address.
     * @return String.
     */
    public String generateDeliveryStatus(String recipient) {
        return "Reporting-MTA: dns; " + Config.getServer().getHostname() + "\r\n" +
                "Received-From-MTA: DNS; " + relaySession.getSession().getFriendRdns() + "\r\n" +
                "Arrival-Date: " + relaySession.getSession().getDate() + "\r\n" +
                "\r\n" +
                "Final-Recipient: RFC822; " + recipient + "\r\n" +
                "Action: failed\r\n" +
                "Status: " + deliveryStatus() + "\r\n" +
                "Remote-MTA: DNS; [" + relaySession.getSession().getFriendAddr() + "]\r\n" +
                "Diagnostic-Code: SMTP; " + relaySession.getRejection() + "\r\n" +
                "Last-Attempt-Date: " + relaySession.getLastRetryDate() + "\r\n";
    }

    private MessageEnvelope originalEnvelope(String recipient) {
        return relaySession.getSession().getEnvelopes().stream()
                .filter(envelope -> envelope.getRcpts() != null && envelope.getRcpts().contains(recipient))
                .findFirst().orElseGet(() -> relaySession.getSession().getEnvelopes().getLast());
    }

    private String deliveryStatus() {
        String rejection = relaySession.getRejection();
        var enhanced = java.util.regex.Pattern.compile("\\b5\\.\\d{1,3}\\.\\d{1,3}\\b").matcher(rejection);
        if (enhanced.find()) {
            return enhanced.group();
        }
        return rejection.startsWith("4") ? "5.4.7" : "5.0.0";
    }
}
