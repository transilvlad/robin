/**
 * Manages the storage of incoming emails for the server.
 *
 * <p>Provides an interface and local disk implementation for server incoming email storage.
 * <br>The default LocalStorageClient can be replaced with another implementation via Factories.
 * <br>Ideally this would be done in a plugin.
 *
 * <p>Example setting new storage client:
 * <pre>
 *     Factories.setStorageClient(RemoteStorageClient::new);
 * </pre>
 *
 * <p>Storage processors can check for chaos headers to force specific return values for testing.
 * <br>This allows testing exception scenarios without actually triggering them.
 * <br>See {@link com.mimecast.robin.mime.headers.ChaosHeaders} for more details.
 *
 * <p>Read more on plugins here: {@link com.mimecast.robin.annotation}
 */
package com.mimecast.robin.storage;
