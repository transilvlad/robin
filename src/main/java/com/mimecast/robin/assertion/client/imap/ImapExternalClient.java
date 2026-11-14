package com.mimecast.robin.assertion.client.imap;

import com.google.common.collect.ImmutableList;
import com.mimecast.robin.assertion.AssertException;
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
 *   // absent: true, // Fail if the message is found instead.
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
     * Last verification failure reason, reported when attempts are spent.
     */
    private String lastFailure = "no email found";

    /**
     * Runs the IMAP client to fetch and verify message.
     *
     * <p>Retries while the message is missing or does not match. With {@code absent: true} the message
     * must not be found on any attempt.
     *
     * @throws AssertException If verification fails or no email is found.
     */
    @Override
    public void run() throws AssertException {
        if (config.isEmpty()) {
            return;
        }

        if (!config.checkCondition(connection)) {
            log.info("Condition not met, skipping: {}", config.getStringProperty("condition"));
            return;
        }

        if (transactionId < 0 || transactionId >= connection.getSession().getEnvelopes().size()) {
            throw new AssertException("IMAP assertion must be configured on an envelope");
        }
        String messageId = connection.getSession().getEnvelopes().get(transactionId).getMessageId();

        boolean absent = config.getBooleanProperty("absent", false);
        boolean deleteAfter = config.getBooleanProperty("delete", false);
        boolean purgeAfter = config.getBooleanProperty("purge", false);

        if (deleteAfter && purgeAfter) {
            log.warn("Both 'delete' and 'purge' are set; 'purge' takes precedence");
        }

        ImapClient imapClient = getClient();
        Message verifiedMessage = null;

        long delay = config.getWait() > 0 ? config.getWait() * 1000L : 0L;
        for (int count = 0; count < config.getRetry(); count++) {
            Sleep.nap((int) delay);
            delay = config.getDelay() * 1000L; // Retry delay.

            Message message = imapClient.fetchEmailByMessageId(messageId);
            if (absent) {
                if (message != null) {
                    fail("IMAP email found when expected absent: " + messageId);
                    return;
                }
            } else if (verifyMessage(message, imapClient)) {
                verifiedMessage = message;
                log.info("AssertExternal IMAP fetch verify success");
                break;
            }

            log.info("AssertExternal IMAP fetch verify {}", (count < config.getRetry() - 1 ? "failure" : "attempts spent"));
        }

        if (absent) {
            log.info("AssertExternal IMAP verify absent success");
            return;
        }

        if (verifiedMessage == null) {
            fail("IMAP email verification failed: " + lastFailure);
            return;
        }

        // Cleanup on success only.
        if (purgeAfter) {
            int purged = imapClient.purgeFolder();
            log.info("AssertExternal IMAP purged {} message(s) from folder", purged);
        } else if (deleteAfter) {
            boolean deleted = imapClient.deleteMessage(verifiedMessage);
            if (deleted) {
                log.info("AssertExternal IMAP deleted verified message");
            } else {
                log.warn("AssertExternal IMAP failed to delete verified message");
            }
        }
    }

    /**
     * Fails the assertion, or skips when verify failures are not fatal.
     *
     * @param message Failure message.
     * @throws AssertException When verify failures are fatal.
     */
    private void fail(String message) throws AssertException {
        if (assertVerifyFails) {
            throw new AssertException(message);
        }
        log.warn("{}, skipping", message);
        skip = true;
    }

    /**
     * Verifies the fetched message against expected criteria.
     *
     * @param message    The email message to verify.
     * @param imapClient The IMAP client for part extraction.
     * @return Boolean indicating verification success.
     * @throws AssertException If the configuration is invalid or the message cannot be read.
     */
    @SuppressWarnings("unchecked")
    private boolean verifyMessage(Message message, ImapClient imapClient) throws AssertException {
        try {
            if (message == null) {
                lastFailure = "no email found";
                return false;
            }

            var matches = config.getMapProperty("matches");
            if (matches.isEmpty()) {
                throw new AssertException("IMAP assertion has no matches configured");
            }

            // Verify headers if configured.
            if (matches.containsKey("headers") &&
                    !verifyHeaders(message, (List<List<String>>) matches.get("headers"))) {
                return false;
            }

            // Verify parts if configured.
            return !matches.containsKey("parts") ||
                    verifyParts(message, imapClient, (List<Map<String, Object>>) matches.get("parts"));

        } catch (MessagingException e) {
            throw new AssertException("Error processing email message: " + e.getMessage());
        }
    }

    /**
     * Verifies message headers against configured patterns.
     *
     * <p>Each group must match a single header line formatted as {@code Name: value}.
     *
     * @param message       The email message.
     * @param headerMatches List of header match pattern groups.
     * @return Boolean indicating verification success.
     * @throws MessagingException If header extraction fails.
     */
    private boolean verifyHeaders(Message message, List<List<String>> headerMatches) throws MessagingException {
        List<String> lines = new ArrayList<>();
        for (var header : ImmutableList.copyOf(message.getAllHeaders().asIterator())) {
            lines.add(header.getName() + ": " + header.getValue());
        }

        for (List<String> group : headerMatches) {
            if (!anyLineMatchesAll(lines, compile(group, 0))) {
                lastFailure = "no header matched " + group;
                return false;
            }
        }

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
     */
    @SuppressWarnings("unchecked")
    private boolean verifyParts(Message message, ImapClient imapClient, List<Map<String, Object>> partSpecs) {
        List<PartDescriptor> parts = imapClient.extractParts(message);
        if (parts.isEmpty()) {
            lastFailure = "no MIME parts found";
            return false;
        }

        for (Map<String, Object> partSpec : partSpecs) {
            List<List<String>> headerPatterns = (List<List<String>>) partSpec.get("headers");
            List<List<String>> bodyPatterns = (List<List<String>>) partSpec.get("body");
            Map<String, String> hashAssertions = (Map<String, String>) partSpec.get("hashes");

            // Find matching part(s) by headers, or use all parts without a header filter.
            List<PartDescriptor> matchingParts = parts;
            if (headerPatterns != null && !headerPatterns.isEmpty()) {
                matchingParts = findMatchingParts(parts, headerPatterns);
                if (matchingParts.isEmpty()) {
                    lastFailure = "no MIME part found matching header patterns: " + headerPatterns;
                    return false;
                }
            }

            // Verify body patterns against matching parts.
            if (bodyPatterns != null && !bodyPatterns.isEmpty()
                    && matchingParts.stream().noneMatch(part -> verifyPartBody(part, bodyPatterns))) {
                lastFailure = "no MIME part body matched patterns: " + bodyPatterns;
                return false;
            }

            // Verify hash assertions against matching parts.
            if (hashAssertions != null && !hashAssertions.isEmpty()
                    && matchingParts.stream().noneMatch(part -> verifyPartHashes(part, hashAssertions))) {
                lastFailure = "no MIME part hashes matched: " + hashAssertions;
                return false;
            }
        }

        return true;
    }

    /**
     * Finds parts where every header pattern group matches one of the part's header lines.
     *
     * @param parts          List of all parts.
     * @param headerPatterns Header pattern groups to match.
     * @return List of matching parts.
     */
    private List<PartDescriptor> findMatchingParts(List<PartDescriptor> parts, List<List<String>> headerPatterns) {
        List<List<Pattern>> groups = new ArrayList<>();
        for (List<String> group : headerPatterns) {
            groups.add(compile(group, 0));
        }

        List<PartDescriptor> matching = new ArrayList<>();
        for (PartDescriptor part : parts) {
            List<String> lines = new ArrayList<>();
            part.headers().forEach((name, value) -> lines.add(name + ": " + value));

            if (groups.stream().allMatch(group -> anyLineMatchesAll(lines, group))) {
                matching.add(part);
            }
        }

        return matching;
    }

    /**
     * Does any single line match every pattern.
     *
     * @param lines    Lines to check.
     * @param patterns Compiled patterns.
     * @return Boolean.
     */
    private static boolean anyLineMatchesAll(List<String> lines, List<Pattern> patterns) {
        return lines.stream().anyMatch(line -> patterns.stream().allMatch(p -> p.matcher(line).find()));
    }

    /**
     * Compiles magic-replaced, case-insensitive patterns.
     *
     * @param patterns Pattern strings.
     * @param flags    Extra Pattern flags.
     * @return List of Pattern.
     */
    private List<Pattern> compile(List<String> patterns, int flags) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            compiled.add(Pattern.compile(Magic.magicReplace(pattern, connection.getSession()), Pattern.CASE_INSENSITIVE | flags));
        }
        return compiled;
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
}
