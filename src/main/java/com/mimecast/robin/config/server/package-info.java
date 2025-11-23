/**
 * Server-specific configuration files and their accessors.
 *
 * <p>This provides accessors for the server configuration files.
 * <br>The default directory is cfg/ but can be overridden with the -c cli parameter.
 *
 * <p>The server configuration is split into multiple files.
 * <br>The main file is server.json5 which contains the core configuration.
 * <br>Other files are loaded if present in the same directory.
 *
 * <p>Server core configuration lives in `server.json5`.
 * <br>External files (auto‑loaded if present in same directory):
 * <ul>
 *     <li>`storage.json5` Email storage options.</li>
 *     <li>`users.json5` Local test users (disabled when Dovecot auth enabled).</li>
 *     <li>`scenarios.json5` SMTP behavior scenarios.</li>
 *     <li>`relay.json5` Automatic relay settings.</li>
 *     <li>`queue.json5` Persistent relay / retry queue.</li>
 *     <li>`prometheus.json5` Prometheus remote write metrics settings.</li>
 *     <li>`dovecot.json5` Socket auth &amp; LDA integration replacing static users.</li>
 *     <li>`webhooks.json5` Per-command HTTP callbacks with optional response override.</li>
 *     <li>`vault.json5` HashiCorp Vault integration settings for secrets management.</li>
 *     <li>`clamav.json5` ClamAV integration for virus scanning.</li>
 * </ul>
 *
 * <h3>Development and Testing Features</h3>
 * <p>Several configuration options are intended strictly for development and testing:
 * <ul>
 *     <li><b>xclientEnabled</b> - Enables XCLIENT extension for forging sender information.
 *         WARNING: Do NOT enable in production.</li>
 *     <li><b>chaosHeaders</b> - Allows forcing specific processor return values via headers.
 *         WARNING: Do NOT enable in production.</li>
 * </ul>
 *
 * @see com.mimecast.robin.main.Server
 */
package com.mimecast.robin.config.server;
