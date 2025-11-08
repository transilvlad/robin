package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Local storage client mock for testing.
 */
class LocalStorageClientMock extends LocalStorageClient {

    private final Pair<Integer, String> result;

    public LocalStorageClientMock(ServerConfig config, Pair<Integer, String> result) {
        this.config = config;
        this.result = result;
    }
}
