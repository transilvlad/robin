package com.mimecast.robin.assertion;

import com.mimecast.robin.assertion.client.ExternalClient;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.smtp.transaction.Transaction;
import com.mimecast.robin.smtp.transaction.TransactionList;
import com.mimecast.robin.util.Magic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Assertion engine.
 *
 * <p>Called at the end of a client delivery.
 * <p>It will read the assertions from the configuration and try to assert them against SMTP transactions and external logs.
 * <p>External logs are only assertable given a client that can provide the logs.
 * <p>The intent of this was initially to fetch and assert MTA logs but was generified.
 *
 * @see Connection
 * @see ExternalClient
 */
public class Assert {
    protected static final Logger log = LogManager.getLogger(Assert.class);

    /**
     * Session instance.
     */
    protected final Connection connection;

    /**
     * Fail test for protocol assertion failure.
     */
    protected Boolean assertProtocolFails;

    /**
     * Skip asserting and exit gracefully.
     */
    protected Boolean skip = false;

    /**
     * Constructs a new Assert instance with given Connection.
     *
     * @param connection Connection instance.
     */
    public Assert(Connection connection) {
        this.connection = connection;
        this.assertProtocolFails = connection.getSession().getAssertions().getProtocolFails(Config.getClient().getAssertions().getProtocolFails());
    }

    /**
     * Have assertions been skipped?
     *
     * @return Boolean.
     */
    public boolean skipped() {
        return skip;
    }

    /**
     * Run assertions.
     *
     * @return Self.
     * @throws AssertException Assertion exception.
     */
    public Assert run() throws AssertException {
        if (!connection.getSession().getAssertions().isEmpty()) {
            assertProtocol(connection.getSession().getAssertions().getProtocol(), connection.getSession().getSessionTransactionList());
        }

        assertEnvelopes();
        assertExternal(connection.getSession().getAssertions().getExternal());

        return this;
    }

    /**
     * Assert against transactions.
     *
     * <p>Each assertion is {@code [COMMAND]}, {@code [COMMAND, regex]} or {@code [COMMAND, regex, selector]}.
     * {@code [COMMAND]} passes when a transaction of that command exists. Without a selector it passes
     * when any transaction of that command matches. Selectors:
     * <ul>
     *     <li>{@code all} - every transaction of that command must match.</li>
     *     <li>{@code none} - no transaction of that command may match.</li>
     *     <li>a zero-based index - that transaction must exist and match.</li>
     * </ul>
     *
     * @param list            Protocol assertions as a list in list.
     * @param transactionList TransactionList instance.
     * @throws AssertException Assertion exception.
     */
    private void assertProtocol(List<List<String>> list, TransactionList transactionList) throws AssertException {
        if (list == null || list.isEmpty()) {
            return;
        }

        for (List<?> assertion : list) {
            if (skip) return;

            if (assertion == null || assertion.isEmpty() || assertion.size() > 3) {
                throw new AssertException("Invalid protocol assertion " + assertion + ", expected [COMMAND], [COMMAND, regex] or [COMMAND, regex, selector]");
            }

            String command = String.valueOf(assertion.get(0));
            String regex = assertion.size() > 1 ? String.valueOf(assertion.get(1)) : "";
            String selector = assertion.size() == 3 ? selector(assertion.get(2)) : "any";

            List<Transaction> transactions = transactionList.getTransactions(command);
            if (transactions.isEmpty() && !selector.equals("none")) {
                protocolFailure("Assert unable to find transaction for [" + command + "]");
                continue;
            }

            Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            switch (selector) {
                case "any" -> {
                    if (transactions.stream().noneMatch(t -> matches(p, t))) {
                        protocolFailure("Assert unable to match [" + command + "] [" + regex + "]");
                    }
                }
                case "all" -> {
                    for (int i = 0; i < transactions.size(); i++) {
                        if (!matches(p, transactions.get(i))) {
                            protocolFailure("Assert unable to match [" + command + "] #" + i + " [" + regex + "]: " + transactions.get(i).getResponse());
                            break;
                        }
                    }
                }
                case "none" -> {
                    for (Transaction transaction : transactions) {
                        if (matches(p, transaction)) {
                            protocolFailure("Assert matched refused [" + command + "] [" + regex + "]: " + transaction.getResponse());
                            break;
                        }
                    }
                }
                default -> {
                    int index = Integer.parseInt(selector);
                    if (index >= transactions.size()) {
                        protocolFailure("Assert unable to find transaction [" + command + "] #" + index + ", found " + transactions.size());
                    } else if (!matches(p, transactions.get(index))) {
                        protocolFailure("Assert unable to match [" + command + "] #" + index + " [" + regex + "]: " + transactions.get(index).getResponse());
                    }
                }
            }
        }
    }

    /**
     * Normalizes a protocol assertion selector.
     *
     * @param value Selector value from configuration; JSON numbers may arrive as Double.
     * @return "any", "all", "none" or a non-negative index as String.
     * @throws AssertException When the selector is not recognized.
     */
    private static String selector(Object value) throws AssertException {
        if (value instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() == Math.floor(number.doubleValue())) {
            return String.valueOf(number.intValue());
        }

        String selector = String.valueOf(value).trim().toLowerCase();
        if (selector.equals("any") || selector.equals("all") || selector.equals("none") || selector.matches("\\d+")) {
            return selector;
        }

        throw new AssertException("Invalid protocol assertion selector [" + value + "], expected any, all, none or an index");
    }

    /**
     * Does the transaction response match.
     *
     * @param pattern     Compiled pattern.
     * @param transaction Transaction instance.
     * @return Boolean.
     */
    private static boolean matches(Pattern pattern, Transaction transaction) {
        return transaction.getResponse() != null && pattern.matcher(transaction.getResponse()).find();
    }

    /**
     * Fails the protocol assertion, or skips the remaining assertions when protocol failures are not fatal.
     *
     * @param message Failure message.
     * @throws AssertException When protocol failures are fatal.
     */
    private void protocolFailure(String message) throws AssertException {
        if (assertProtocolFails) {
            throw new AssertException(message);
        }
        log.warn("{}, skipping", message);
        skip = true;
    }

    /**
     * Assert against session envelopes.
     *
     * @throws AssertException Assertion exception.
     */
    private void assertEnvelopes() throws AssertException {
        if (skip) return; // Skip asserting and exit gracefully.

        if (!connection.getSession().getEnvelopes().isEmpty() && !connection.getSession().getSessionTransactionList().getEnvelopes().isEmpty()) {
            for (int i = 0; i < connection.getSession().getEnvelopes().size(); i++) {
                MessageEnvelope envelope = connection.getSession().getEnvelopes().get(i);
                List<EnvelopeTransactionList> envelopeTransactions = connection.getSession().getSessionTransactionList().getEnvelopes();
                if (envelopeTransactions.size() > i) {
                    EnvelopeTransactionList envelopeTransactionList = envelopeTransactions.get(i);

                    if (envelope.getAssertions() != null) {
                        if (!envelope.getAssertions().getProtocol().isEmpty()) {
                            assertProtocol(envelope.getAssertions().getProtocol(), envelopeTransactionList);
                        }

                        // External.
                        if (!envelope.getAssertions().getExternal().isEmpty()) {
                            // Add envelope and transaction magic for assertions to use.
                            Magic.putEnvelopeMagic(i, connection.getSession());
                            Magic.putTransactionMagic(i, connection.getSession());

                            assertExternal(envelope.getAssertions().getExternal(), i);
                        }
                    }
                } else {
                    throw new AssertException("Assert envelope " + i + " not found");
                }
            }
        }
    }

    /**
     * Assert external if any with given assertions list.
     *
     * @param assertions List of BasicConfig instances.
     * @throws AssertException Assertion exception.
     */
    protected void assertExternal(List<BasicConfig> assertions) throws AssertException {
        assertExternal(assertions, -1);
    }

    /**
     * Assert external if any with given assertions list and transaction id.
     *
     * @param assertions    List of BasicConfig instances.
     * @param transactionId Transaction ID.
     * @throws AssertException Assertion exception.
     */
    private void assertExternal(List<BasicConfig> assertions, int transactionId) throws AssertException {
        if (Factories.getExternalKeys().isEmpty() || skip) return; // Skip asserting and exit gracefully.

        List<String> keys = Factories.getExternalKeys();
        for (BasicConfig assertion : assertions) {
            if (!assertion.isEmpty() && keys.contains(assertion.getStringProperty("type"))) {

                ExternalClient client = Factories.getExternalClient(assertion.getStringProperty("type"), connection, assertion);
                if (client != null) {
                    if (transactionId >= 0) {
                        client.setTransactionId(transactionId);
                    }
                    client.run();
                    skip = client.skip();

                } else {
                    throw new AssertException("Assert external client not instanciated");
                }
            }
        }
    }
}
