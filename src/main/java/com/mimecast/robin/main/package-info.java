/**
 * The entry point and core components of the Robin application.
 *
 * <p>This package contains the main classes for the Robin application, which can be run from the command line.
 *
 * <h2>Client/ClientCLI</h2>
 * <p>SMTP delivery client.
 * <br>Can be invoked via CLI or programmatically.
 *
 * <p>While it can function like a standard email client, it was primarily designed for testing.
 * <br>Making it language agnostic allows QA engineers to write tests easily without the need to know Java.
 *
 * <p>In this example we simply provide a config directory and a case JSON file:
 * <pre>
 *     &#64;Test
 *     void lipsum() throws AssertException, IOException, ConfigurationException {
 *         new Client("cfg/")
 *                 .send("src/test/resources/cases/config/lipsum.json5");
 *     }
 * </pre>
 *
 * <h2>Server/ServerCLI</h2>
 * <p>SMTP receipt server.
 * <br>Can be invoked via CLI or programmatically.
 * <br>It only requires a directory path for its config files.
 *
 * <h2>MTA-STS</h2>
 * <p>Robin MTA-STS client tool.
 *
 * <h2>Foundation</h2>
 * <p>The foundation abstract ensures the configurations are loaded just once along with any plugins.
 *
 * <h2>Extensions</h2>
 * <p>SMTP extensions.
 * <br>This being an SMTP client/server it operates based on SMTP extensions.
 * <br>These are implemented in pairs of classes one for each side (client/server).
 * <br>All standard extensions are provided, XCLIENT extension is offered via a plugin.
 *
 * <h2>Factories</h2>
 * <p>For all other pluggable components.
 * <br>Interfaces and default implementations are provide din most cases.
 * <ul>
 *     <li><b>Behaviour</b> - Dictates the behaviour of the SMTP client. <i>With new extensions come new behaviours.</i>
 *     <li><b>Session</b> - Holds the SMTP session data for both client and server. <i>With new extensions come new responsibilities.</i>
 *     <li><b>TLSSocket</b> - Negotiates the TLS handshake for STARTTLS SMTP extension.
 *     <li><b>X509TrustManager</b> - Provides the means to validate remote certificates.
 *     <li><b>DigestCache</b> - Designed to hold the authentication cache for Digest-MD5 authentication mechanism subsequent authentication support. <i>When a dev gets bored.</i>
 *     <li><b>StorageClient</b> - Provides the means to save incoming emails to the disk or remotely.
 *     <li><b>ExternalClient</b> - Provides the means to fetch logs from other services to assert against (like MTA logs). <i>No default provided.</i>
 * </ul>
 *
 * <h2>Config</h2>
 * <p>Static container for client, server and properties configuration files.
 * <ul>
 *     <li><b>Client</b> - Client configuration defaults (these can be overridden by a case config) and predefined routes.
 *     <li><b>Server</b> - Server configuration, including authentication users and rejection scenarios.
 *     <li><b>Properties</b> - Universal configuration that also accesses system properties.
 * </ul>
 */
package com.mimecast.robin.main;
