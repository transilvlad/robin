package com.mimecast.robin.assertion.client.imap;

import com.google.common.collect.ImmutableList;
import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.assertion.AssertExternalGroup;
import com.mimecast.robin.assertion.client.MatchExternalClient;
import com.mimecast.robin.imap.ImapClient;
import com.mimecast.robin.imap.ImapClient.PartDescriptor;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.Sleep;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMAP external client for email verification assertions.
 *
 * <p>Connects to an IMAP server, fetches a message by Message-ID, and verifies
 * its headers and/or body parts against configured patterns.
 *
 * <p>Supports optional cleanup after successful verification:
 * <ul>
 *   <li>{@code delete: true} - Deletes the verified message after assertion.</li>
 *   <li>{@code purge: true} - Purges all messages in the folder after assertion (takes precedence over delete).</li>
 * </ul>
 *
 * <p>Example configuration:
 * <pre>
 * {
 *   type: "imap",
 *   host: "imap.example.com",
 *   port: 993,
 *   user: "test@example.com",
 *   pass: "secret",
 *   folder: "INBOX",
 *   delete: true,
 *   matches: {
 *     headers: [
 *       ["subject", "Test Email"]
 *     ],
 *     parts: [
 *       {
 *         headers: [["content-type", "text/plain"]],
 *         body: [["Hello, World"]]
 *       }
 *     ]
 *   }
 * }
 * </pre>
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

            boolean deleteAfter = config.getBooleanProperty("delete", false);
            boolean purgeAfter = config.getBooleanProperty("purge", false);

            if (deleteAfter && purgeAfter) {
                log.warn("Both 'delete' and 'purge' are set; 'purge' takes precedence");
            }

            ImapClient imapClient = getClient();
            Message verifiedMessage = null;
            boolean verified = false;

            long delay = config.getWait() > 0 ? config.getWait() * 1000L : 0L;
            for (int count = 0; count < config.getRetry(); count++) {
                Sleep.nap((int) delay);

                Message message = imapClient.fetchEmailByMessageId(
                        connection.getSession().getEnvelopes().get(transactionId).getMessageId()
                );
                verified = verifyMessage(message, imapClient);
                if (verified) {
                    verifiedMessage = message;
                    log.info("AssertExternal IMAP fetch verify success");
                    break;
                }

                delay = config.getDelay() * 1000L; // Retry delay.
                log.info("AssertExternal IMAP fetch verify {}", (count < config.getRetry() - 1 ? "failure" : "attempts spent"));

                if (!assertVerifyFails) {
                    skip = true;
                    log.warn("Skipping");
                    return;
                }
            }

            if (!verified) {
                throw new AssertException("IMAP email verification failed or no email found");
            }

            // Cleanup on success only.
            if (purgeAfter) {
                int purged = imapClient.purgeFolder();
                log.info("AssertExternal IMAP purged {} message(s) from folder", purged);
            } else if (deleteAfter && verifiedMessage != null) {
                boolean deleted = imapClient.deleteMessage(verifiedMessage);
                if (deleted) {
                    log.info("AssertExternal IMAP deleted verified message");
                } else {
                    log.warn("AssertExternal IMAP failed to delete verified message");
                }
            }
        }
    }

    /**
     * Verifies the fetched message against expected criteria.
     *
     * @param message    The email message to verify.
     * @param imapClient The IMAP client for part extraction.
     * @return Boolean indicating verification success.
     * @throws AssertException If verification fails.
     */
    @SuppressWarnings("unchecked")
    private boolean verifyMessage(Message message, ImapClient imapClient) throws AssertException {
        try {
            if (message == null) {
                return false;
            }

            var matches = config.getMapProperty("matches");
            if (matches.isEmpty()) {
                log.error("No matches found in assertion configuration");
                return false;
            }

            // Verify headers if configured.
            if (matches.containsKey("headers") &&
                    !verifyHeaders(message, (List<List<String>>) matches.get("headers"))) {
                return false;
            }

            // Verify parts if configured.
            if (matches.containsKey("parts") &&
                    !verifyParts(message, imapClient, (List<Map<String, Object>>) matches.get("parts"))) {
                return false;
            }

            return true;

        } catch (MessagingException e) {
            throw new AssertException("Error processing email message: " + e.getMessage());
        }
    }

    /**
     * Verifies message headers against configured patterns.
     *
     * @param message       The email message.
     * @param headerMatches List of header match pattern groups.
     * @return Boolean indicating verification success.
     * @throws MessagingException If header extraction fails.
     * @throws AssertException    If pattern matching fails.
     */
    private boolean verifyHeaders(Message message, List<List<String>> headerMatches) throws MessagingException, AssertException {
        var headers = ImmutableList.copyOf(message.getAllHeaders().asIterator());
        if (headers.isEmpty()) {
            log.error("No headers found in the email message");
            return false;
        }

        // Clear and compile patterns for headers.
        matchGroups.clear();
        compilePatterns(headerMatches);

        for (var header : headers) {
            for (var group : matchGroups) {
                matchEntry(group, header.getName() + ": " + header.getValue(), true);
            }
        }

        verifyMatches();
        return true;
    }

    /**
     * Verifies message MIME parts against configured patterns.
     *
     * <p>Each part assertion can specify optional header patterns to target a specific
     * MIME part, plus body patterns and hash values to match against the part's content.
     *
     * @param message    The email message.
     * @param imapClient The IMAP client for part extraction.
     * @param partSpecs  List of part specification maps.
     * @return Boolean indicating verification success.
     * @throws AssertException If pattern matching fails.
     */
    @SuppressWarnings("unchecked")
    private boolean verifyParts(Message message, ImapClient imapClient, List<Map<String, Object>> partSpecs) throws AssertException {
        List<PartDescriptor> parts = imapClient.extractParts(message);
        if (parts.isEmpty()) {
            log.error("No MIME parts found in the email message");
            return false;
        }

        for (Map<String, Object> partSpec : partSpecs) {
            List<List<String>> headerPatterns = (List<List<String>>) partSpec.get("headers");
            List<List<String>> bodyPatterns = (List<List<String>>) partSpec.get("body");
            Map<String, String> hashAssertions = (Map<String, String>) partSpec.get("hashes");

            // Find matching part(s) by headers.
            List<PartDescriptor> matchingParts = findMatchingParts(parts, headerPatterns);

            if (matchingParts.isEmpty()) {
                if (headerPatterns != null && !headerPatterns.isEmpty()) {
                    throw new AssertException("No MIME part found matching header patterns: " + headerPatterns);
                }
                // No header filter, use all parts.
                matchingParts = parts;
            }

            // Verify body patterns against matching parts.
            if (bodyPatterns != null && !bodyPatterns.isEmpty()) {
                boolean bodyMatched = false;
                for (PartDescriptor part : matchingParts) {
                    if (verifyPartBody(part, bodyPatterns)) {
                        bodyMatched = true;
                        break;
                    }
                }
                if (!bodyMatched) {
                    throw new AssertException("No MIME part body matched patterns: " + bodyPatterns);
                }
            }

            // Verify hash assertions against matching parts.
            if (hashAssertions != null && !hashAssertions.isEmpty()) {
                boolean hashMatched = false;
                for (PartDescriptor part : matchingParts) {
                    if (verifyPartHashes(part, hashAssertions)) {
                        hashMatched = true;
                        break;
                    }
                }
                if (!hashMatched) {
                    throw new AssertException("No MIME part hashes matched: " + hashAssertions);
                }
            }
        }

        return true;
    }

    /**
     * Finds parts that match all specified header patterns.
     *
     * @param parts          List of all parts.
     * @param headerPatterns Header patterns to match (optional).
     * @return List of matching parts.
     */
    private List<PartDescriptor> findMatchingParts(List<PartDescriptor> parts, List<List<String>> headerPatterns) {
        List<PartDescriptor> matching = new ArrayList<>();

        if (headerPatterns == null || headerPatterns.isEmpty()) {
            return matching;
        }

        for (PartDescriptor part : parts) {
            boolean allGroupsMatch = true;

            for (List<String> patternGroup : headerPatterns) {
                boolean groupMatched = false;
                List<Pattern> compiled = new ArrayList<>();
                for (String pattern : patternGroup) {
                    compiled.add(Pattern.compile(Magic.magicReplace(pattern, connection.getSession()), Pattern.CASE_INSENSITIVE));
                }

                // Check if all patterns in the group match any header.
                int matchedCount = 0;
                for (Map.Entry<String, String> header : part.headers().entrySet()) {
                    String headerLine = header.getKey() + ": " + header.getValue();
                    for (Pattern p : compiled) {
                        if (p.matcher(headerLine).find()) {
                            matchedCount++;
                            break;
                        }
                    }
                }

                if (matchedCount == compiled.size()) {
                    groupMatched = true;
                }

                if (!groupMatched) {
                    allGroupsMatch = false;
                    break;
                }
            }

            if (allGroupsMatch) {
                matching.add(part);
            }
        }

        return matching;
    }

    /**
     * Verifies a part's body content against patterns.
     *
     * @param part         The part to verify.
     * @param bodyPatterns Body patterns to match.
     * @return Boolean indicating if all patterns matched.
     */
    private boolean verifyPartBody(PartDescriptor part, List<List<String>> bodyPatterns) {
        String body = part.body();
        if (body == null || body.isEmpty()) {
            return false;
        }

        for (List<String> patternGroup : bodyPatterns) {
            boolean groupMatched = false;

            for (String pattern : patternGroup) {
                Pattern compiled = Pattern.compile(
                        Magic.magicReplace(pattern, connection.getSession()),
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
                );
                Matcher matcher = compiled.matcher(body);
                if (matcher.find()) {
                    groupMatched = true;
                    log.debug("Body pattern matched: {}", pattern);
                    break;
                }
            }

            if (!groupMatched) {
                log.debug("Body pattern group did not match: {}", patternGroup);
                return false;
            }
        }

        return true;
    }

    /**
     * Verifies a part's content hashes against expected values.
     *
     * @param part           The part to verify.
     * @param hashAssertions Map of hash type (sha1, sha256, md5) to expected value.
     * @return Boolean indicating if all hashes matched.
     */
    private boolean verifyPartHashes(PartDescriptor part, Map<String, String> hashAssertions) {
        for (Map.Entry<String, String> entry : hashAssertions.entrySet()) {
            String hashType = entry.getKey().toLowerCase();
            String expected = Magic.magicReplace(entry.getValue(), connection.getSession());
            String actual = part.hashes().get(hashType);

            if (actual == null || !actual.equals(expected)) {
                log.debug("Hash mismatch for {}: expected={}, actual={}", hashType, expected, actual);
                return false;
            }
            log.debug("Hash matched for {}: {}", hashType, actual);
        }
        return true;
    }

    /**
     * Compiles header match patterns.
     *
     * @param headerMatches List of header match patterns.
     */
    private void compilePatterns(List<List<String>> headerMatches) {
        for (List<String> list : headerMatches) {
            List<Pattern> compiled = new ArrayList<>();
            for (String assertion : list) {
                compiled.add(Pattern.compile(Magic.magicReplace(assertion, connection.getSession()), Pattern.CASE_INSENSITIVE));
            }

            matchGroups.add(new AssertExternalGroup().setPatterns(compiled));
        }
    }
}
