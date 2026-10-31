package com.mimecast.robin.smtp.transaction;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * EnvelopeTransactionList.
 *
 * <p>This provides the implementation for envelope transactions.
 *
 * @see TransactionList
 */
public class EnvelopeTransactionList extends TransactionList implements Cloneable {
    // Preserve the computed UID from histories written before the LMTP final ledger.
    @Serial
    private static final long serialVersionUID = 3715280498593334553L;

    /**
     * Gets MAIL transaction.
     *
     * @return MAIL transaction instance.
     */
    public Transaction getMail() {
        return !getTransactions("MAIL").isEmpty() ? getTransactions("MAIL").get(0) : null;
    }

    /**
     * Gets RCPT transactions.
     *
     * @return RCPT transactions list.
     */
    public List<Transaction> getRcpt() {
        return getTransactions("RCPT");
    }

    /** Per-recipient final delivery replies, distinct from LMTP RCPT admission. */
    public List<Transaction> getLmtp() {
        return getTransactions("LMTP");
    }

    /**
     * Gets all recipients from RCPT commands.
     *
     * @return List of String.
     */
    public List<String> getRecipients() {
        List<String> recipients = new ArrayList<>();
        for (Transaction transaction : getTransactions("RCPT")) {
            if (transaction.getCommand().equalsIgnoreCase("rcpt") && transaction.getAddress() != null) {
                recipients.add(transaction.getAddress());
            }
        }

        return recipients;
    }

    /**
     * Gets failed recipients from RCPT admission or LMTP final replies.
     *
     * @return List of String.
     */
    public List<String> getFailedRecipients() {
        List<String> failedRecipients = new ArrayList<>();
        for (Transaction transaction : getTransactions("RCPT")) {
            if (transaction.getCommand().equalsIgnoreCase("rcpt") && transaction.isError() && transaction.getAddress() != null) {
                failedRecipients.add(transaction.getAddress());
            }
        }
        for (Transaction transaction : getLmtp()) {
            if (transaction.isError() && transaction.getAddress() != null
                    && !failedRecipients.contains(transaction.getAddress())) {
                failedRecipients.add(transaction.getAddress());
            }
        }

        return failedRecipients;
    }

    /**
     * Gets RCPT errors logs.
     *
     * @return List of Transaction.
     */
    public List<Transaction> getRcptErrors() {
        List<Transaction> found = new ArrayList<>();
        for (Transaction transaction : getRcpt()) {
            if (transaction.isError()) {
                found.add(transaction);
            }
        }

        return found;
    }

    /**
     * Gets DATA transaction.
     *
     * @return DATA transaction instance.
     */
    public Transaction getData() {
        return !getTransactions("DATA").isEmpty() ? getTransactions("DATA").get(0) : null;
    }

    /**
     * Gets BDAT transactions.
     *
     * @return BDAT transactions list.
     */
    public List<Transaction> getBdat() {
        return getTransactions("BDAT");
    }

    /**
     * Confirms aggregate SMTP DATA/BDAT acceptance, not LMTP per-recipient delivery.
     *
     * @return Whether a successful final DATA or BDAT LAST reply was recorded.
     */
    public boolean isMessageCommitted() {
        Transaction data = getData();
        if (data != null && !data.isError() && data.getResponseCode().startsWith("2")) return true;
        List<Transaction> chunks = getBdat();
        return !chunks.isEmpty() && chunks.stream().noneMatch(Transaction::isError)
                && chunks.getLast().getPayload() != null
                && chunks.getLast().getPayload().toUpperCase(Locale.ROOT).contains(" LAST")
                && chunks.getLast().getResponseCode().startsWith("2");
    }

    /**
     * Retains failed or unknown LMTP recipients, excluding confirmed final deliveries.
     * Aggregate confirmation remains supported for scripted clients without a final ledger.
     *
     * @param requested Original delivery recipients.
     * @return Recipients that still need failure handling.
     */
    public List<String> getUndeliveredLmtpRecipients(List<String> requested) {
        var confirmed = new LinkedHashSet<String>();
        List<Transaction> replies = getLmtp();
        if (replies.isEmpty() && isMessageCommitted()) {
            replies = getRcpt();
            if (replies.isEmpty()) confirmed.addAll(requested);
        }
        for (Transaction reply : replies) {
            if (!reply.isError() && reply.getResponseCode().startsWith("2") && reply.getAddress() != null) {
                confirmed.add(reply.getAddress());
            }
        }
        return requested.stream().filter(recipient -> !confirmed.contains(recipient)).toList();
    }

    /**
     * Deep clone this EnvelopeTransactionList.
     *
     * @return cloned EnvelopeTransactionList instance.
     */
    @Override
    public EnvelopeTransactionList clone() {
        EnvelopeTransactionList clone = new EnvelopeTransactionList();

        for (Transaction t : this.getTransactions()) {
            String cmd = t.getCommand();
            String payload = t.getPayload();
            String response = t.getResponse();
            boolean error = t.isError();

            if (payload != null) {
                clone.addTransaction(cmd, payload, response != null ? response : "", error);
            } else if (response != null) {
                clone.addTransaction(cmd, response, error);
            } else {
                clone.addTransaction(cmd, "");
            }
        }

        return clone;
    }
}
