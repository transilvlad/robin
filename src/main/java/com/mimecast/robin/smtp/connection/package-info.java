/**
 * SMTP connection management with security policy enforcement.
 * <p>This package provides core connection handling for SMTP sessions with integrated support
 * <br>for DANE (RFC 7672) and MTA-STS (RFC 8461) security policies.
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.mimecast.robin.smtp.connection.Connection} - Main connection controller</li>
 *   <li>{@link com.mimecast.robin.smtp.connection.SmtpFoundation} - Base class for socket operations and TLS</li>
 *   <li>{@link com.mimecast.robin.smtp.connection.SmtpException} - SMTP-specific exceptions</li>
 *   <li>{@link com.mimecast.robin.smtp.connection.ConnectionPool} - Connection pooling for outbound delivery</li>
 * </ul>
 *
 * <h2>Connection Lifecycle</h2>
 * <ol>
 *   <li><strong>Create</strong> - {@code new Connection(session)}</li>
 *   <li><strong>Connect</strong> - {@code connection.connect()} establishes socket</li>
 *   <li><strong>STARTTLS</strong> - Negotiated via {@link com.mimecast.robin.smtp.extension.client.ClientStartTls}
 *       <ul>
 *         <li>Mandatory if {@link com.mimecast.robin.smtp.security.SecurityPolicy} requires it</li>
 *         <li>Enforces DANE TLSA validation or MTA-STS PKI validation</li>
 *       </ul>
 *   </li>
 *   <li><strong>Process</strong> - SMTP commands via behaviour processors</li>
 *   <li><strong>Close</strong> - {@code connection.close()} releases resources</li>
 * </ol>
 *
 * <h2>Security Policy Integration</h2>
 * <p>Connections enforce security policies set on the {@link com.mimecast.robin.smtp.session.Session}:
 * <pre>{@code
 * // Set security policy before connecting.
 * session.setSecurityPolicy(securityPolicy);
 *
 * // Create connection.
 * Connection connection = new Connection(session);
 * connection.connect();
 *
 * // TLS enforcement happens automatically in ClientStartTls:
 * // - DANE policy: TLS MANDATORY, certificate validated against TLSA records.
 * // - MTA-STS policy: TLS MANDATORY, certificate validated via PKI.
 * // - Opportunistic: TLS attempted if advertised, cleartext acceptable.
 * }</pre>
 *
 * <h2>TLS Negotiation</h2>
 * <p>TLS is negotiated through {@link com.mimecast.robin.smtp.connection.SmtpFoundation#startTLS(boolean)}:
 * <ul>
 *   <li>Uses {@link com.mimecast.robin.smtp.security.TLSSocket} factory</li>
 *   <li>Passes {@link com.mimecast.robin.smtp.security.SecurityPolicy} for enforcement</li>
 *   <li>DANE policies trigger {@link com.mimecast.robin.smtp.security.DaneTrustManager}</li>
 *   <li>MTA-STS policies use standard PKI validation</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>When security policies are violated:
 * <ul>
 *   <li><strong>DANE</strong> - Connection fails if STARTTLS not advertised or TLSA validation fails</li>
 *   <li><strong>MTA-STS</strong> - Connection fails if STARTTLS not advertised or PKI validation fails</li>
 *   <li><strong>Opportunistic</strong> - Connection proceeds with or without TLS</li>
 * </ul>
 *
 * @see com.mimecast.robin.smtp.security
 * @see com.mimecast.robin.smtp.session
 * @see com.mimecast.robin.mx
 * @see <a href="https://tools.ietf.org/html/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc3207">RFC 3207 - STARTTLS</a>
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 */
package com.mimecast.robin.smtp.connection;
