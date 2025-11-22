/**
 * SMTP security components including TLS, DANE, and MTA-STS support.
 * <p>This package provides security policy enforcement for SMTP connections per RFC 7672 (DANE)
 * <br>and RFC 8461 (MTA-STS).
 *
 * <h2>Key Components</h2>
 *
 * <h3>Security Policy System</h3>
 * <ul>
 *   <li>{@link com.mimecast.robin.smtp.security.SecurityPolicy} - Represents DANE/MTA-STS/Opportunistic policies</li>
 *   <li>{@link com.mimecast.robin.smtp.security.SecureMxRecord} - MX record with associated security policy</li>
 * </ul>
 *
 * <h3>TLS Components</h3>
 * <ul>
 *   <li>{@link com.mimecast.robin.smtp.security.TLSSocket} - Interface for TLS socket creation</li>
 *   <li>{@link com.mimecast.robin.smtp.security.DefaultTLSSocket} - Standard TLS implementation</li>
 *   <li>{@link com.mimecast.robin.smtp.security.DaneTrustManager} - DANE-aware certificate validator</li>
 * </ul>
 *
 * <h2>Security Policy Priority (RFC 8461 Section 2)</h2>
 * <ol>
 *   <li><strong>DANE</strong> - If TLSA records exist, DANE policy applies (MTA-STS is ignored)</li>
 *   <li><strong>MTA-STS</strong> - If no DANE and MTA-STS policy exists, MTA-STS applies</li>
 *   <li><strong>OPPORTUNISTIC</strong> - No security policy, TLS is opportunistic</li>
 * </ol>
 *
 * <h2>DANE (RFC 7672)</h2>
 * <p>DNS-Based Authentication of Named Entities for SMTP.
 * <p><strong>Requirements when DANE TLSA records are present:</strong>
 * <ul>
 *   <li>TLS is <strong>MANDATORY</strong></li>
 *   <li>Server certificate <strong>MUST</strong> validate against TLSA records</li>
 *   <li>Validation failure results in message delay/bounce (no cleartext fallback)</li>
 * </ul>
 *
 * <p><strong>TLSA Record Format:</strong> {@code _25._tcp.<mxhostname> IN TLSA usage selector matching data}
 * <p><strong>Supported TLSA Usage Types:</strong>
 * <ul>
 *   <li>Usage 0 (PKIX-TA): CA constraint</li>
 *   <li>Usage 1 (PKIX-EE): Service certificate constraint</li>
 *   <li>Usage 2 (DANE-TA): Trust anchor assertion</li>
 *   <li>Usage 3 (DANE-EE): Domain-issued certificate (most common)</li>
 * </ul>
 *
 * <h2>MTA-STS (RFC 8461)</h2>
 * <p>SMTP MTA Strict Transport Security.
 * <p><strong>Requirements when MTA-STS policy is present:</strong>
 * <ul>
 *   <li>TLS is <strong>MANDATORY</strong></li>
 *   <li>Server certificate <strong>MUST</strong> validate via Web PKI</li>
 *   <li>MX hostname <strong>MUST</strong> match policy mx patterns</li>
 *   <li>Validation failure results in message delay/bounce (no cleartext fallback)</li>
 * </ul>
 *
 * <p><strong>Policy Modes:</strong>
 * <ul>
 *   <li><strong>enforce</strong>: Strict enforcement - delivery fails on validation errors</li>
 *   <li><strong>testing</strong>: Report-only mode - deliver despite validation failures</li>
 *   <li><strong>none</strong>: No active policy</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Resolve MX with security policies.
 * MXResolver resolver = new MXResolver();
 * List<SecureMxRecord> secureMxList = resolver.resolveSecureMx("example.com");
 *
 * for (SecureMxRecord secureMx : secureMxList) {
 *     SecurityPolicy policy = secureMx.getSecurityPolicy();
 *     session.setSecurityPolicy(policy);
 *
 *     // Connect - TLS will be enforced per policy.
 *     connection.connect();
 *     // ... SMTP conversation.
 * }
 * }</pre>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - DANE TLSA</a>
 */
package com.mimecast.robin.smtp.security;
