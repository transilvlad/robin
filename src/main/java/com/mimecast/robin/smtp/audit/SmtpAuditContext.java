package com.mimecast.robin.smtp.audit;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects bounded SMTP lifecycle data and emits one structured record per completed
 * message and connection.
 */
public final class SmtpAuditContext {
    private static final Logger AUDIT_LOG = LogManager.getLogger("com.mimecast.robin.audit");

    private final long connectionStartedNanos = System.nanoTime();
    private final String listener;
    private final AtomicBoolean connectionRecorded = new AtomicBoolean();

    private int commandCount;
    private int acceptedMessages;
    private int rejectedMessages;
    private long messageBytes;
    private int lastSmtpStatus;
    private String terminationReason = "client_disconnect";
    private String exceptionType = "";

    public SmtpAuditContext(String listener) {
        this.listener = value(listener);
    }

    /**
     * Records one SMTP command received from the peer.
     */
    public synchronized void recordCommand() {
        commandCount++;
    }

    /**
     * Records the last SMTP response status sent to the peer.
     *
     * @param response Raw SMTP response bytes.
     */
    public synchronized void recordResponse(byte[] response) {
        if (response == null || response.length < 3) {
            return;
        }
        String prefix = new String(response, 0, 3, StandardCharsets.US_ASCII);
        if (Character.isDigit(prefix.charAt(0))
                && Character.isDigit(prefix.charAt(1))
                && Character.isDigit(prefix.charAt(2))) {
            lastSmtpStatus = Integer.parseInt(prefix);
        }
    }

    /**
     * Sets the reason the SMTP connection processing loop ended.
     */
    public synchronized void setTerminationReason(String reason) {
        terminationReason = value(reason);
    }

    /**
     * Records an exception that terminated connection processing.
     */
    public synchronized void setException(Throwable error) {
        terminationReason = "exception";
        exceptionType = error == null ? "" : value(error.getClass().getSimpleName());
    }

    /**
     * Emits one terminal outcome for a DATA or final BDAT command.
     */
    public synchronized void recordMessage(
            Session session,
            MessageEnvelope envelope,
            String protocol,
            String successfulOutcome,
            boolean processed,
            long bytes,
            long durationMillis) {
        String outcome = resolveOutcome(successfulOutcome, processed, lastSmtpStatus);
        if (isAccepted(outcome)) {
            acceptedMessages++;
        } else {
            rejectedMessages++;
        }
        messageBytes += Math.max(0, bytes);

        AUDIT_LOG.info(
                "event=message_outcome session_uid={} direction={} protocol={} outcome={} smtp_status={} "
                        + "message_id={} sender_domain={} recipient_domains={} recipient_count={} bytes={} duration_ms={}",
                value(session.getUID()),
                session.isOutbound() ? "outbound" : "inbound",
                value(protocol),
                outcome,
                statusValue(),
                envelope == null ? "" : value(envelope.getMessageId()),
                envelope == null ? "" : domain(envelope.getMail()),
                envelope == null ? "" : recipientDomains(envelope),
                envelope == null ? 0 : envelope.getRcpts().size(),
                Math.max(0, bytes),
                Math.max(0, durationMillis));
    }

    /**
     * Emits the connection summary once.
     */
    public void recordConnection(Session session) {
        if (!connectionRecorded.compareAndSet(false, true)) {
            return;
        }

        long durationMillis = (System.nanoTime() - connectionStartedNanos) / 1_000_000;
        int commands;
        int accepted;
        int rejected;
        long bytes;
        int status;
        String reason;
        String exception;
        synchronized (this) {
            commands = commandCount;
            accepted = acceptedMessages;
            rejected = rejectedMessages;
            bytes = messageBytes;
            status = lastSmtpStatus;
            reason = terminationReason;
            exception = exceptionType;
        }

        AUDIT_LOG.info(
                "event=connection_outcome session_uid={} remote_ip={} remote_rdns={} listener={} direction={} "
                        + "tls={} authenticated={} commands={} envelopes={} accepted={} rejected={} bytes={} "
                        + "smtp_status={} termination_reason={} exception_type={} duration_ms={}",
                value(session.getUID()),
                value(session.getFriendAddr()),
                value(session.getFriendRdns()),
                listener,
                session.isOutbound() ? "outbound" : "inbound",
                session.isTls(),
                session.isAuth(),
                commands,
                session.getEnvelopes().size(),
                accepted,
                rejected,
                bytes,
                status == 0 ? "" : status,
                reason,
                exception,
                Math.max(0, durationMillis));
    }

    /**
     * @return Number of SMTP commands received on this connection.
     */
    public synchronized int getCommandCount() {
        return commandCount;
    }

    /**
     * @return Number of accepted terminal DATA/BDAT outcomes.
     */
    public synchronized int getAcceptedMessages() {
        return acceptedMessages;
    }

    /**
     * @return Number of rejected terminal DATA/BDAT outcomes.
     */
    public synchronized int getRejectedMessages() {
        return rejectedMessages;
    }

    /**
     * @return Total message bytes observed in terminal DATA/BDAT outcomes.
     */
    public synchronized long getMessageBytes() {
        return messageBytes;
    }

    /**
     * @return Last SMTP response status sent to the peer, or zero if none.
     */
    public synchronized int getLastSmtpStatus() {
        return lastSmtpStatus;
    }

    /**
     * @return Bounded reason the connection processing loop ended.
     */
    public synchronized String getTerminationReason() {
        return terminationReason;
    }

    private String resolveOutcome(String successfulOutcome, boolean processed, int smtpStatus) {
        if (smtpStatus >= 500) {
            return "perm_rejected";
        }
        if (smtpStatus >= 400) {
            return "temp_rejected";
        }
        if (!processed) {
            return "failed";
        }
        return value(successfulOutcome);
    }

    private boolean isAccepted(String outcome) {
        return "accepted".equals(outcome)
                || "blackholed".equals(outcome)
                || "proxied".equals(outcome);
    }

    private String statusValue() {
        return lastSmtpStatus == 0 ? "" : Integer.toString(lastSmtpStatus);
    }

    private static String recipientDomains(MessageEnvelope envelope) {
        Set<String> domains = new LinkedHashSet<>();
        for (String recipient : envelope.getRcpts()) {
            String domain = domain(recipient);
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }
        return String.join(",", domains);
    }

    private static String domain(String address) {
        if (address == null) {
            return "";
        }
        String normalized = address.replace("<", "").replace(">", "").trim();
        int separator = normalized.lastIndexOf('@');
        if (separator < 0 || separator == normalized.length() - 1) {
            return "";
        }
        return value(normalized.substring(separator + 1).toLowerCase(Locale.ROOT));
    }

    private static String value(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[^A-Za-z0-9._,:@+/-]", "_");
    }
}
