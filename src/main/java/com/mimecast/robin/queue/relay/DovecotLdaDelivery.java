package com.mimecast.robin.queue.relay;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.queue.RelaySession;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /**
     * Sends the email using Dovecot LDA.
     *
     * @return DovecotLdaDelivery instance.
     */
    public DovecotLdaDelivery send() {
        for (String recipient : relaySession.getSession().getEnvelopes().getLast().getRcpts()) {

            int exitCode = -1;
            try {
                // Configure command.
                List<String> command = new ArrayList<>(Arrays.asList(
                        Config.getServer().getDovecotLdaBinary(),
                        "-d", relaySession.getSession().getEnvelopes().getLast().getMail(),
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
                exitCode = process.waitFor();

                // Log result.
                if (exitCode == 0) {
                    log.info("Dovecot-LDA delivery successfully");
                } else {
                    log.error("Dovecot-LDA delivery failed with exit code: {}, error: {}", exitCode, error);
                }
            } catch (Exception e) {
                log.error("Dovecot-LDA delivery failed with exception: {}", e.getMessage());
            }

            if (exitCode != 0) {
                relaySession.getSession().getSessionTransactionList().addTransaction("LDA", "Dovecot-LDA delivery failed for " + recipient, true);
            }
        }

        return this;
    }
}
