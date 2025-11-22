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
 * <h2>Storage Processor Chain:</h2>
 * <p>Robin executes a chain of storage processors after email receipt.
 * <br>Processors execute in order and any processor can reject or quarantine the message.
 *
 * <p>Default processor chain (in execution order):
 * <ol>
 *     <li>{@link com.mimecast.robin.storage.AVStorageProcessor} - ClamAV virus scanning</li>
 *     <li>{@link com.mimecast.robin.storage.SpamStorageProcessor} - Rspamd spam/phishing detection</li>
 *     <li>{@link com.mimecast.robin.storage.DovecotStorageProcessor} - Delivery via Dovecot LDA protocol</li>
 *     <li>{@link com.mimecast.robin.storage.LocalStorageProcessor} - Write to disk storage</li>
 * </ol>
 *
 * <p>Custom processors can be added via plugins using {@code Factories.addStorageProcessor()}.
 * <br>Each processor returns a {@link com.mimecast.robin.storage.StorageProcessorResult} indicating
 * <br>the disposition (ACCEPT, REJECT, QUARANTINE) and any associated message.
 *
 * <p>Storage processors can check for chaos headers to force specific return values for testing.
 * <br>This allows testing exception scenarios without actually triggering them.
 * <br>See {@link com.mimecast.robin.mime.headers.ChaosHeaders} for more details.
 *
 * <p>Read more on plugins here: {@link com.mimecast.robin.annotation}
 *
 * @see com.mimecast.robin.storage.StorageProcessor
 * @see com.mimecast.robin.storage.StorageProcessorResult
 */
package com.mimecast.robin.storage;
