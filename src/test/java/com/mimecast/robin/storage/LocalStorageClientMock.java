package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;

/**
 * Local storage client mock for testing.
 */
public class LocalStorageClientMock extends LocalStorageClient {

    public LocalStorageClientMock(ServerConfig config) {
        this.config = config;
    }
}
