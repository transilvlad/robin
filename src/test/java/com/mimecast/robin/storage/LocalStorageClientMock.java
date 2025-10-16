package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.queue.relay.DovecotLdaDeliveryMock;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Local storage client mock for testing.
 */
class LocalStorageClientMock extends LocalStorageClient {

    private final Pair<Integer, String> result;

    public LocalStorageClientMock(BasicConfig config, Pair<Integer, String> result) {
        this.config = config;
        this.result = result;
    }

    protected DovecotLdaDelivery getDovecotLdaDeliveryInstance() {
        return new DovecotLdaDeliveryMock(new RelaySession(connection.getSession()), result);
    }
}
