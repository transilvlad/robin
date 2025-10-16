package com.mimecast.robin.queue.relay;

import com.mimecast.robin.queue.RelaySession;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;

/**
 * Dovecot LDA delivery mock for testing.
 */
public class DovecotLdaDeliveryMock extends DovecotLdaDelivery {

    private final Pair<Integer, String> result;

    public DovecotLdaDeliveryMock(RelaySession relaySession, Pair<Integer, String> result) {
        super(relaySession);
        this.result = result;
    }

    @Override
    protected Pair<Integer, String> callDovecotLda(String recipient) throws IOException, InterruptedException {
        return result;
    }
}
