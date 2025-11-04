package com.mimecast.robin.queue.relay;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dovecot LDA delivery.
 * <p>This provides the implementation for delivering emails using Dovecot LDA.
 * <p>It only supports single envelopes at a time given the way storage implementation works.
 * See: LocalStorageClient.class
 */
public class DovecotLdaDelivery {
    private static final Logger log = LogManager.getLogger(DovecotLdaDelivery.class);

    private final RelaySession relaySession;

    /**
     * Constructs new DovecotLDARelay instance.
     *
     * @param relaySession RelaySession instance.
     */
    public DovecotLdaDelivery(RelaySession relaySession) {
        this.relaySession = relaySession;
    }

    /**
     * Sends the email using Dovecot LDA.
     *
     * @return DovecotLdaDelivery instance.
     */
    public DovecotLdaDelivery send() {
        relaySession.getSession().getSessionTransactionList().addEnvelope(new EnvelopeTransactionList());

        if (!relaySession.getSession().getEnvelopes().isEmpty()) {
            Pair<Integer, String> result = null;
            if (relaySession.getSession().isInbound()) {
                for (String recipient : relaySession.getSession().getEnvelopes().getLast().getRcpts()) {
                    try {
                        result = callDovecotLda(recipient);

                        // Log result.
                        if (result.getKey() == 0) {
                            log.info("Dovecot-LDA delivery successful for recipient: {}", recipient);
                        } else {
                            log.error("Dovecot-LDA delivery failed for recipient: {} with exit code: {}, error: {}", recipient, result.getKey(), result.getValue());
                        }
                    } catch (Exception e) {
                        log.error("Dovecot-LDA delivery failed for recipient: {} with exception: {}", recipient, e.getMessage());
                    }

                    if (result == null || result.getKey() != 0) {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("RCPT", "RCPT TO:<" + recipient + ">", SmtpResponses.DOVECOT_LDA_FAILED_550, true);
                    } else {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("RCPT", "RCPT TO:<" + recipient + ">", SmtpResponses.DOVECOT_LDA_SUCCESS_250, false);
                    }
                }
            } else {
                try {
                    String sender = relaySession.getSession().getEnvelopes().getLast().getMail();
                    result = callDovecotLda(sender);

                    if (result == null || result.getKey() != 0) {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("MAIL", "MAIL FROM:<" + sender + ">", SmtpResponses.DOVECOT_LDA_FAILED_550, true);
                    } else {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("MAIL", "MAIL FROM:<" + sender + ">", SmtpResponses.DOVECOT_LDA_SUCCESS_250, false);
                    }
                } catch (IOException | InterruptedException e) {
                    log.error("Dovecot-LDA delivery failed for sender: {} with exception: {}", relaySession.getSession().getEnvelopes().getLast().getMail(), e.getMessage());
                }
            }
        } else {
            log.warn("No envelopes found in the last session for Dovecot-LDA delivery.");
        }

        return this;
    }

    /**
     * Calls Dovecot LDA with the given recipient.
     *
     * @param recipient Recipient email address.
     * @return Pair of exit code and error message.
     * @throws IOException          On I/O errors.
     * @throws InterruptedException On process interruption.
     */
    protected Pair<Integer, String> callDovecotLda(String recipient) throws IOException, InterruptedException {
        // Configure command.
        List<String> command = new ArrayList<>(Arrays.asList(
                Config.getServer().getDovecot().getStringProperty("ldaBinary"),
                "-d", recipient,
                "-f", relaySession.getSession().getEnvelopes().getLast().getMail(),
                "-a", recipient,
                // "There can only be one" envelope when relaying. See: RelayMessage.class
                "-p", relaySession.getSession().getEnvelopes().getFirst().getFile()
        ));

        if (StringUtils.isNotBlank(relaySession.getMailbox())) {
            command.add("-m");
            command.add(relaySession.getMailbox());
        }

        // Instantiate process builder and start running.
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        // Get error string.
        String error = new String(process.getErrorStream().readAllBytes());

        // Wait for process to finish and get exit code.
        int exitCode = process.waitFor();

        return Pair.of(exitCode, error);
    }
}
