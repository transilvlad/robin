package com.mimecast.robin.smtp.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * EnvelopeTransactionList.
 *
 * <p>This provides the implementation for envelope transactions.
 *
 * @see TransactionList
 */
public class EnvelopeTransactionList extends TransactionList {

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
     * Gets failed recipients from RCPT commands.
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
}
