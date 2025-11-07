/**
 * The main package for Robin, a versatile MTA Server and Tester.
 *
 * <p>Robin MTA Server and Tester is a development, debug and testing tool for MTA architects.
 * <br>However, as the name suggests it can also be used as a lightweight MTA server with Dovecot SASL AUTH and mailbox integration.
 * <br>It is powered by a highly customizable SMTP client designed to emulate the behaviour of popular email clients.
 *
 * <p>The lightweight server is ideal for a simple configurable catch server for testing or a fully-fledged MTA using
 * <br>Dovecot mailboxes or web hooks. It provides Prometheus and Graphite metrics with Prometheus remote write built in
 * <br>plus a multitude or other handy endpoints.
 *
 * <p>This project can be compiled into a runnable JAR.
 * <br>A CLI interface is implemented with support for both client and server execution.
 *
 * <h2>CLI usage:</h2>
 * <pre>
 *      $ java -jar robin.jar
 *      MTA server and tester
 *
 *      usage:   [--client] [--mtasts] [--server]
 *      --client   Run as client
 *      --server   Run as server
 *      --mtasts   Run as MTA-STS client
 * </pre>
 *
 * <h2>CLI usage client:</h2>
 * <pre>
 *      $ java -jar robin.jar --client
 *      Email delivery client
 *
 *      usage:   [-c &lt;arg&gt;] [-f &lt;arg&gt;] [-h] [-j &lt;arg&gt;] [-m &lt;arg&gt;] [-p &lt;arg&gt;] [-r &lt;arg&gt;] [-x &lt;arg&gt;]
 *      -c,--conf &lt;arg&gt;    Path to configuration dir (Default: cfg/)
 *      -f,--file &lt;arg&gt;    EML file to send
 *      -h,--help         Show usage help
 *      -j,--gson &lt;arg&gt;    Path to case file JSON
 *      -m,--mail &lt;arg&gt;    MAIL FROM address
 *      -p,--port &lt;arg&gt;    Port to connect to
 *      -r,--rcpt &lt;arg&gt;    RCPT TO address
 *      -x,--mx &lt;arg&gt;      Server to connect to
 * </pre>
 *
 * <h2>CLI usage MTA-STS:</h2>
 * <pre>
 *      $ java -jar robin.jar --mtasts
 *      Robin MTA-STS client tool
 *
 *      usage:   [-d &lt;arg&gt;] [-f &lt;arg&gt;] [-j] [-m &lt;arg&gt;]
 *      -d,--domain &lt;arg&gt;   Domain
 *      -f,--file &lt;arg&gt;     Write policy details to JSON file
 *      -j,--json           Show policy details as JSON
 *      -m,--mx &lt;arg&gt;       MX to match against policy MX masks
 * </pre>
 *
 * <h2>CLI usage server:</h2>
 * <pre>
 *      $ java -jar robin.jar --server
 *      MTA server
 *
 *      usage:   [&lt;arg&gt;]
 *      Path to configuration directory
 *
 *      example:
 *      java -jar robin.jar --server cfg/
 * </pre>
 *
 * <p>Mimecast uses this to run smoke tests every time a new MTA snapshot is built.
 * <br>This helps identify bugs early before leaving the development environment.
 */
package com.mimecast.robin;
