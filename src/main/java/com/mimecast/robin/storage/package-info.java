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
 *     <li>{@link com.mimecast.robin.storage.DovecotStorageProcessor} - Delivery via Dovecot LDA or LMTP backend (configurable)</li>
 *     <li>{@link com.mimecast.robin.storage.LocalStorageProcessor} - Write to disk storage</li>
 * </ol>
 *
 * <p>DovecotStorageProcessor supports two mailbox delivery backends:
 * <ul>
 *   <li>LDA (Local Delivery Agent): Requires Robin and Dovecot in the same container, uses UNIX socket and binary.</li>
 *   <li>LMTP (Local Mail Transfer Protocol): Default, uses a configurable LMTP server list, works with SQL auth and does not require Robin and Dovecot in the same container.</li>
 * </ul>
 * Backend selection is automatic: the system checks which backend is enabled (<code>saveLda.enabled</code> or <code>saveLmtp.enabled</code>). LMTP takes precedence if both are enabled.
 * Backend-specific options are grouped under <code>saveLda</code> and <code>saveLmtp</code> config objects.
 * Shared options (inline save, failure behaviour, max retry count) are top-level.
 *
 * <p>Custom processors can be added via plugins using {@code Factories.addStorageProcessor()}.
 * <br>Each processor returns a boolean indicating success (true) or failure (false).
 *
 * <p>Storage processors can check for chaos headers to force specific return values for testing.
 * <br>This allows testing exception scenarios without actually triggering them.
 * <br>See {@link com.mimecast.robin.mime.headers.ChaosHeaders} for more details.
 *
 * <p>Read more on plugins here: {@link com.mimecast.robin.annotation}
 *
 * @see com.mimecast.robin.storage.StorageProcessor
 */
package com.mimecast.robin.storage;
