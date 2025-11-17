package com.mimecast.robin.queue.relay;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.storage.LocalStorageClient;
import com.mimecast.robin.util.Sleep;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dovecot LDA client.
 * <p>This provides the implementation for delivering emails using Dovecot LDA.
 * <p>It only supports single envelopes at a time given the way storage implementation works.
 *
 * @see LocalStorageClient
 */
public class DovecotLdaClient {
    private static final Logger log = LogManager.getLogger(DovecotLdaClient.class);

    private final RelaySession relaySession;
    
    /**
     * Chaos headers for testing exceptions.
     */
    private ChaosHeaders chaosHeaders;

    /**
     * Constructs new DovecotLdaClient instance.
     *
     * @param relaySession RelaySession instance.
     */
    public DovecotLdaClient(RelaySession relaySession) {
        this.relaySession = relaySession;
    }

    /**
     * Sets chaos headers for testing.
     *
     * @param chaosHeaders ChaosHeaders instance.
     * @return Self.
     */
    public DovecotLdaClient setChaosHeaders(ChaosHeaders chaosHeaders) {
        this.chaosHeaders = chaosHeaders;
        return this;
    }

    /**
     * Sends the email using Dovecot LDA.
     *
     * @return DovecotLdaClient instance.
     */
    public DovecotLdaClient send() {
        relaySession.getSession().getSessionTransactionList().addEnvelope(new EnvelopeTransactionList());

        if (!relaySession.getSession().getEnvelopes().isEmpty()) {
            // Read max attempts from dovecot config; default to 1 if not present or invalid.
            int maxAttempts = Math.toIntExact(Config.getServer().getDovecot().getLongProperty("inlineSaveMaxAttempts", 1L));
            if (maxAttempts < 1) {
                maxAttempts = 1;
            }

            // Get retry delay from config, default to 0 if not present
            int retryDelay = Math.toIntExact(Config.getServer().getDovecot().getLongProperty("inlineSaveRetryDelay", 0L));

            Pair<Integer, String> result = null;
            if (relaySession.getSession().isInbound()) {
                // Attempt per-recipient deliveries with retries.
                for (String recipient : relaySession.getSession().getEnvelopes().getLast().getRcpts()) {
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            if (attempt > 1 && retryDelay > 0) {
                                Sleep.nap(retryDelay);
                            }
                            result = callDovecotLda(recipient);

                            if (result.getKey() == 0) {
                                if (attempt == 1) {
                                    log.info("Dovecot-LDA delivery successful for recipient: {}", recipient);
                                } else {
                                    log.info("Dovecot-LDA delivery successful for recipient: {} after {} attempts", recipient, attempt);
                                }
                                break; // success
                            } else {
                                if (attempt < maxAttempts) {
                                    log.warn("Attempt {} of {} Dovecot-LDA delivery failed for recipient: {} exitCode={} error={} (will retry)", attempt, maxAttempts, recipient, result.getKey(), StringUtils.abbreviate(result.getValue(), 500));
                                } else {
                                    log.error("Attempt {} of {} Dovecot-LDA delivery failed for recipient: {} exitCode={} error={} (giving up)", attempt, maxAttempts, recipient, result.getKey(), StringUtils.abbreviate(result.getValue(), 500));
                                }
                            }
                        } catch (IOException | InterruptedException e) {
                            if (attempt < maxAttempts) {
                                log.warn("Attempt {} of {} Dovecot-LDA delivery threw exception for recipient: {} message={} (will retry)", attempt, maxAttempts, recipient, e.getMessage());
                            } else {
                                log.error("Attempt {} of {} Dovecot-LDA delivery threw exception for recipient: {} message={} (giving up)", attempt, maxAttempts, recipient, e.getMessage());
                            }
                        }
                    }

                    // Record transaction result for this recipient.
                    if (result == null || result.getKey() != 0) {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("RCPT", "RCPT TO:<" + recipient + ">", SmtpResponses.DOVECOT_LDA_FAILED_550, true);
                    } else {
                        relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("RCPT", "RCPT TO:<" + recipient + ">", SmtpResponses.DOVECOT_LDA_SUCCESS_250, false);
                    }
                }
            } else {
                // Outbound: single sender delivery, with retries.
                String sender = relaySession.getSession().getEnvelopes().getLast().getMail();

                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        if (attempt > 1 && retryDelay > 0) {
                            Sleep.nap(retryDelay);
                        }
                        result = callDovecotLda(sender);

                        if (result.getKey() == 0) {
                            if (attempt == 1) {
                                log.info("Dovecot-LDA delivery successful for sender: {}", sender);
                            } else {
                                log.info("Dovecot-LDA delivery successful for sender: {} after {} attempts", sender, attempt);
                            }
                            break; // success
                        } else {
                            if (attempt < maxAttempts) {
                                log.warn("Attempt {} of {} Dovecot-LDA delivery failed for sender: {} exitCode={} error={} (will retry)", attempt, maxAttempts, sender, result.getKey(), StringUtils.abbreviate(result.getValue(), 500));
                            } else {
                                log.error("Attempt {} of {} Dovecot-LDA delivery failed for sender: {} exitCode={} error={} (giving up)", attempt, maxAttempts, sender, result.getKey(), StringUtils.abbreviate(result.getValue(), 500));
                            }
                        }
                    } catch (IOException | InterruptedException e) {
                        if (attempt < maxAttempts) {
                            log.warn("Attempt {} of {} Dovecot-LDA delivery threw exception for sender: {} message={} (will retry)", attempt, maxAttempts, sender, e.getMessage());
                        } else {
                            log.error("Attempt {} of {} Dovecot-LDA delivery threw exception for sender: {} message={} (giving up)", attempt, maxAttempts, sender, e.getMessage());
                        }
                    }
                }

                // Record transaction result for sender.
                if (result == null || result.getKey() != 0) {
                    relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("MAIL", "MAIL FROM:<" + sender + ">", SmtpResponses.DOVECOT_LDA_FAILED_550, true);
                } else {
                    relaySession.getSession().getSessionTransactionList().getEnvelopes().getLast().addTransaction("MAIL", "MAIL FROM:<" + sender + ">", SmtpResponses.DOVECOT_LDA_SUCCESS_250, false);
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
        // Check for chaos headers if enabled and present.
        if (Config.getServer().isChaosHeaders() && chaosHeaders != null && chaosHeaders.hasHeaders()) {
            for (MimeHeader header : chaosHeaders.getByValue("DovecotLdaClient")) {
                String recipientParam = header.getParameter("recipient");
                String exitCodeParam = header.getParameter("exitCode");
                String messageParam = header.getParameter("message");
                
                if (recipientParam != null && exitCodeParam != null && recipientParam.equalsIgnoreCase(recipient)) {
                    // Parse exit code and message parameters.
                    try {
                        int exitCode = Integer.parseInt(exitCodeParam);
                        String error = messageParam != null ? messageParam : "";
                        
                        log.warn("Chaos header bypassing Dovecot LDA call for recipient: {} with result: exitCode={} error={}", 
                                 recipient, exitCode, error);
                        return Pair.of(exitCode, error);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid chaos header exitCode format for recipient: {} - exitCode parameter '{}' is not a valid integer. Ignoring chaos header.", 
                                 recipient, exitCodeParam);
                        // Continue with normal LDA call if chaos header is malformed.
                    }
                }
            }
        }
        
        List<String> command = new ArrayList<>(Arrays.asList(
                Config.getServer().getDovecot().getStringProperty("ldaBinary"),
                "-d", recipient,
                "-p", relaySession.getSession().getEnvelopes().getFirst().getFile()
        ));

        if (StringUtils.isNotBlank(relaySession.getMailbox())) {
            command.add("-m");
            command.add(relaySession.getMailbox());
        }

        log.debug("Running command: {}", command);

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
