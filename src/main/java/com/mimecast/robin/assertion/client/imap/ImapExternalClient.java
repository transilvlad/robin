package com.mimecast.robin.assertion.client.imap;

import com.google.common.collect.ImmutableList;
import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.assertion.AssertExternalGroup;
import com.mimecast.robin.assertion.client.MatchExternalClient;
import com.mimecast.robin.imap.ImapClient;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.Sleep;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IMAP external client.
 */
public class ImapExternalClient extends MatchExternalClient {

    /**
     * Gets new ImapClient.
     *
     * @return ImapClient instance.
     */
    protected ImapClient getClient() {
        return new ImapClient(
                Magic.magicReplace(config.getStringProperty("host", "localhost"), connection.getSession()),
                config.getLongProperty("port", 993L),
                Magic.magicReplace(config.getStringProperty("user"), connection.getSession()),
                Magic.magicReplace(config.getStringProperty("pass"), connection.getSession()),
                Magic.magicReplace(config.getStringProperty("folder", "INBOX"), connection.getSession())
        );
    }

    /**
     * Runs the IMAP client to fetch and verify message.
     *
     * @throws AssertException If verification fails or no email is found.
     */
    @Override
    public void run() throws AssertException {
        if (!config.isEmpty()) {
            if (!config.checkCondition(connection)) {
                log.info("Condition not met, skipping: {}", config.getStringProperty("condition"));
                return;
            }
            ImapClient imapClient = getClient();

            boolean verified = false;

            long delay = config.getWait() > 0 ? config.getWait() * 1000L : 0L;
            for (int count = 0; count < config.getRetry(); count++) {
                Sleep.nap((int) delay);

                verified = verifyMessage(imapClient.fetchEmailByMessageId(connection.getSession().getEnvelopes().get(transactionId).getMessageId()));
                if (verified) {
                    log.info("AssertExternal IMAP fetch verify success");
                    return;
                }

                delay = config.getDelay() * 1000L; // Retry delay.
                log.info("AssertExternal IMAP fetch verify {}", (count < config.getRetry() - 1 ? "failure" : "attempts spent"));

                if (!assertVerifyFails) {
                    skip = true;
                    log.warn("Skipping");
                }
            }

            // Fail case if verification failed.
            if (!verified) {
                throw new AssertException("IMAP email verification failed or no email found");
            }
        }
    }

    /**
     * Verifies the fetched message against expected criteria.
     *
     * @param message The email message to verify.
     * @return Boolean indicating verification success.
     * @throws AssertException If verification fails.
     */
    @SuppressWarnings("unchecked")
    private boolean verifyMessage(Message message) throws AssertException {
        try {
            // Check if message is null.
            if (message == null) {
                return false;
            }

            // Extract all headers from the message.
            var headers = ImmutableList.copyOf(message.getAllHeaders().asIterator());
            if (headers.isEmpty()) {
                log.error("No headers found in the email message");
                return false;
            }

            // Get the matches from assertion configuration.
            var matches = config.getMapProperty("matches");
            if (matches.isEmpty()) {
                log.error("No matches found in assertion configuration");
                return false;
            }

            if (!matches.containsKey("headers")) {
                log.error("No match headers found in assertion configuration");
                return false;
            }

            // Compile header match patterns.
            compilePatterns((List<List<String>>) matches.get("headers"));

            for (var header : headers) {
                for (var group : matchGroups) {
                    matchEntry(group, header.getName() + ": " + header.getValue(), true);
                }
            }

            verifyMatches();

        } catch (MessagingException e) {
            throw new AssertException("Error processing email message: " + e.getMessage());
        }

        return true;
    }

    /**
     * Compiles header match patterns.
     *
     * @param headerMatches List of header match patterns.
     */
    private void compilePatterns(List<List<String>> headerMatches) {
        // Make new list of assertions with precompiled patterns for performance.
        // Additionally, we need a result field to track matches.
        for (List<String> list : headerMatches) {
            List<Pattern> compiled = new ArrayList<>();
            for (String assertion : list) {
                compiled.add(Pattern.compile(Magic.magicReplace(assertion, connection.getSession()), Pattern.CASE_INSENSITIVE));
            }

            matchGroups.add(new AssertExternalGroup().setPatterns(compiled));
        }
    }
}
