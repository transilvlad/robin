package com.mimecast.robin.queue.relay;

import com.mimecast.robin.queue.RelayQueue;
import com.mimecast.robin.queue.RelaySession;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dovecot LDA delivery.
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

    public void send() {
        List<InternetAddress> success = new ArrayList<>();
        for (InternetAddress recipient : relaySession.getSession().getRcpts()) {

            int exitCode = -1;
            try {
                // Configure command.
                List<String> command = new ArrayList<>(Arrays.asList(
                        "/usr/lib/dovecot/dovecot-lda",
                        "-d", relaySession.getSession().getMail().getAddress(),
                        "-f", relaySession.getSession().getMail().getAddress(),
                        "-a", recipient.getAddress(),
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
                exitCode = process.waitFor();

                // Log result.
                if (exitCode == 0) {
                    log.info("Dovecot-LDA delivery successfully");
                    success.add(recipient);
                } else {
                    log.error("Dovecot-LDA delivery failed with exit code: {}, error: {}", exitCode, error);
                }
            } catch (Exception e) {
                log.error("Dovecot-LDA delivery failed with exception: {}", e.getMessage());
            }

            if (exitCode != 0) {
                relaySession.getSession().getSessionTransactionList().addTransaction("LDA", "Dovecot-LDA delivery failed for " + recipient.getAddress(), true);
            }
        }

        if (relaySession.getSession().getRcpts().size() != success.size()) {
            relaySession.getSession().getRcpts().removeAll(success);
            new RelayQueue().enqueue(relaySession);
        }
    }
}
