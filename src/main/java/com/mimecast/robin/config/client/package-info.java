/**
 * Client-specific configuration files and their accessors.
 *
 * <p>This provides accessors for the client configuration file.
 * <br>The default file is cfg/client.json5 but can be overridden with the -c cli parameter.
 *
 * <p>The client configuration is separated in two parts.
 * <br>One is the default case which can be overridden by a case file.
 * <br>The other is a list of routes that can be used by cases.
 *
 * <p>A case is a JSON file that defines a scenario to be tested.
 * <br>It can be used to send an email, an http request or both.
 *
 * <p>Client core configuration lives in `client.json5`.
 * <br>External files (auto‑loaded if present in same directory):
 * <ul>
 *     <li>`routes.json5` Static routing table for deterministic target selection for testing.</li>
 * </ul>
 *
 * @see com.mimecast.robin.main.Client
 */
package com.mimecast.robin.config.client;
