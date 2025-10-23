package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.queue.relay.DovecotLdaDeliveryMock;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Local storage client mock for testing.
 */
class LocalStorageClientMock extends LocalStorageClient {

    private final Pair<Integer, String> result;

    public LocalStorageClientMock(ServerConfig config, Pair<Integer, String> ldaResult) {
        this.config = config;
        this.result = ldaResult;
    }

    protected DovecotLdaDelivery getDovecotLdaDeliveryInstance() {
        return new DovecotLdaDeliveryMock(new RelaySession(connection.getSession()), result);
    }
}
