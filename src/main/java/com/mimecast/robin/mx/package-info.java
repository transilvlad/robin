/**
 * MX record resolution with DANE and MTA-STS support.
 * <p>This package provides MX record resolution with integrated security policy determination
 * <br>according to RFC 7672 (DANE) and RFC 8461 (MTA-STS).
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.mimecast.robin.mx.MXResolver} - Main MX resolution with DANE/MTA-STS priority</li>
 *   <li>{@link com.mimecast.robin.mx.StrictMx} - MTA-STS policy fetching and MX filtering</li>
 *   <li>{@link com.mimecast.robin.mx.StrictTransportSecurity} - MTA-STS policy retrieval and validation</li>
 *   <li>{@link com.mimecast.robin.mx.MXRoute} - MX route clustering for bulk operations</li>
 * </ul>
 *
 * <h2>Resolution Priority (RFC 8461 Section 2)</h2>
 * <p>Per RFC 8461 Section 2: <em>"senders who implement MTA-STS validation MUST NOT allow
 * <br>MTA-STS Policy validation to override a failing DANE validation."</em>
 *
 * <p><strong>Resolution Order:</strong>
 * <ol>
 *   <li><strong>DANE (RFC 7672)</strong> - Check for TLSA records at {@code _25._tcp.<mxhostname>}
 *       <ul>
 *         <li>If TLSA records exist, DANE policy applies</li>
 *         <li>TLS becomes MANDATORY with certificate validation against TLSA</li>
 *         <li>MTA-STS is skipped (DANE takes absolute precedence)</li>
 *       </ul>
 *   </li>
 *   <li><strong>MTA-STS (RFC 8461)</strong> - If no DANE, check for MTA-STS policy
 *       <ul>
 *         <li>Fetch policy from {@code https://mta-sts.<domain>/.well-known/mta-sts.txt}</li>
 *         <li>TLS becomes MANDATORY with PKI certificate validation</li>
 *         <li>MX hosts must match policy patterns</li>
 *       </ul>
 *   </li>
 *   <li><strong>Opportunistic</strong> - If neither DANE nor MTA-STS available
 *       <ul>
 *         <li>TLS is attempted if advertised (STARTTLS)</li>
 *         <li>Cleartext fallback is acceptable</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Secure MX Resolution (Recommended)</h3>
 * <pre>{@code
 * MXResolver resolver = new MXResolver();
 * List<SecureMxRecord> secureMxList = resolver.resolveSecureMx("example.com");
 *
 * for (SecureMxRecord secureMx : secureMxList) {
 *     String mxHost = secureMx.getHostname();
 *     int priority = secureMx.getPriority();
 *     SecurityPolicy policy = secureMx.getSecurityPolicy();
 *
 *     // Set policy on session before connecting.
 *     session.setSecurityPolicy(policy);
 *     session.setMx(List.of(mxHost));
 *
 *     // Connect - TLS enforcement happens automatically.
 *     connection.connect();
 * }
 * }</pre>
 *
 * <h3>Legacy MX Resolution</h3>
 * <pre>{@code
 * MXResolver resolver = new MXResolver();
 * List<DnsRecord> mxList = resolver.resolveMx("example.com");
 * // Returns MX records but without security policy information.
 * }</pre>
 *
 * <h3>Bulk Domain Resolution with Clustering</h3>
 * <pre>{@code
 * List<String> domains = List.of("example1.com", "example2.com", "example3.com");
 * MXResolver resolver = new MXResolver();
 * List<MXRoute> routes = resolver.resolveRoutes(domains);
 *
 * // Routes cluster domains with identical MX topology.
 * for (MXRoute route : routes) {
 *     List<String> domainsInRoute = route.getDomains();
 *     List<MXServer> servers = route.getServers();
 *     // ... process clustered domains.
 * }
 * }</pre>
 *
 * <h2>DANE Support</h2>
 * <p>See {@link com.mimecast.robin.mx.dane} package for DANE TLSA record checking.
 *
 * <h2>Important Notes</h2>
 * <ul>
 *   <li>DANE requires DNSSEC-validated DNS responses (not currently enforced in XBillDnsRecordClient)</li>
 *   <li>MTA-STS requires HTTPS connectivity to fetch policies</li>
 *   <li>Security policies are enforced in {@link com.mimecast.robin.smtp.extension.client.ClientStartTls}</li>
 *   <li>Certificate validation for DANE is performed by {@link com.mimecast.robin.smtp.security.DaneTrustManager}</li>
 * </ul>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 */
package com.mimecast.robin.mx;
