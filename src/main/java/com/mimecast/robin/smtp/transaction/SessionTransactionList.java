package com.mimecast.robin.smtp.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Session transaction list.
 *
 * <p>This provides the implementation for session transactions.
 *
 * @see TransactionList
 */
public class SessionTransactionList extends TransactionList implements Cloneable {

    /**
     * Gets last SMTP transaction of defined verb.
     *
     * @param verb Verb string.
     * @return Transaction instance.
     */
    public Transaction getLast(String verb) {
        return !getTransactions(verb).isEmpty() ? getTransactions(verb).get((getTransactions(verb).size() - 1)) : null;
    }

    /**
     * Session envelopes.
     */
    private List<EnvelopeTransactionList> envelopes = new ArrayList<>();

    /**
     * Clears transactions and envelope transaction lists.
     *
     * @return SessionTransactionList instance.
     */
    @Override
    public SessionTransactionList clear() {
        super.clear();
        envelopes.clear();
        return this;
    }

    /**
     * Adds envelope to list.
     *
     * @param envelopeTransactionList EnvelopeTransactionList instance.
     */
    public void addEnvelope(EnvelopeTransactionList envelopeTransactionList) {
        envelopes.add(envelopeTransactionList);
    }

    /**
     * Gets envelopes.
     *
     * @return List of EnvelopeTransactionList.
     */
    public List<EnvelopeTransactionList> getEnvelopes() {
        return envelopes;
    }

    /**
     * Deep clone this SessionTransactionList.
     *
     * @return cloned SessionTransactionList instance.
     */
    @Override
    public SessionTransactionList clone() {
        SessionTransactionList clone = new SessionTransactionList();

        // Copy top-level transactions.
        for (Transaction t : this.getTransactions()) {
            String cmd = t.getCommand();
            String payload = t.getPayload();
            String response = t.getResponse();
            boolean error = t.isError();

            if (payload != null) {
                clone.addTransaction(cmd, payload, response != null ? response : "", error);
            } else if (response != null) {
                clone.addTransaction(cmd, response, error);
            }
        }

        // Clone envelope transaction lists.
        for (EnvelopeTransactionList env : this.envelopes) {
            clone.addEnvelope(env.clone());
        }

        return clone;
    }
}
