/**
 * Handles the core configuration of the Robin application, including Dovecot integration.
 *
 * <p>Provides the configuration foundation and utilities.
 * <br>Also provides accessors for components, including Dovecot authentication and mailbox delivery backends.
 *
 * <p>Dovecot integration supports two authentication backends:
 * <ul>
 *   <li><b>authSocket</b>: Dovecot UNIX socket authentication and userdb lookup (enabled via <code>authSocket.enabled</code>).</li>
 *   <li><b>authSql</b>: SQL authentication backend (enabled via <code>authSql.enabled</code>).</li>
 * </ul>
 * Backend selection is standardized using the <code>enabled</code> flag in each backend config object.
 *
 * <p>Mailbox delivery supports two backends:
 * <ul>
 *   <li><b>saveLda</b>: Local Delivery Agent (LDA), requires Robin and Dovecot in the same container.</li>
 *   <li><b>saveLmtp</b>: Local Mail Transfer Protocol (LMTP), recommended for distributed and SQL-backed setups.</li>
 * </ul>
 *
 * <p>The Log4j2 XML filename can be configured via properties.json5 or a system property called <i>log4j2</i>.
 * <br><b>Example:</b>
 * <pre>java -jar robin.jar --server cfg/ -Dlog4j2=log4j2custom.xml</pre>
 *
 * <p>The properties.json5 filename can be configured via a system property called <i>properties</i>.
 * <br><b>Example:</b>
 * <pre>java -jar robin.jar --server cfg/ -Dproperties=properties-new.json5</pre>
 */
package com.mimecast.robin.config;
