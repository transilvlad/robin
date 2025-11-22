/**
 * DNS-Based Authentication of Named Entities (DANE) support for SMTP.
 *
 * <p>DANE uses TLSA DNS records to associate TLS certificates with mail servers,
 * <br>providing an additional layer of authentication beyond traditional PKI.
 *
 * <p>For SMTP, TLSA records are published at _25._tcp.&lt;mx-hostname&gt; and contain:
 * <ul>
 *     <li><b>Certificate Usage</b> - How the certificate should be used (0-3)</li>
 *     <li><b>Selector</b> - What part of the certificate to match (0=full cert, 1=public key)</li>
 *     <li><b>Matching Type</b> - How to match (0=exact, 1=SHA-256, 2=SHA-512)</li>
 *     <li><b>Certificate Data</b> - The certificate or hash to match</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * String mxHost = "mx.example.com";
 * List&lt;DaneRecord&gt; records = DaneChecker.checkDane(mxHost);
 * if (!records.isEmpty()) {
 *     System.out.println("DANE is enabled for " + mxHost);
 *     for (DaneRecord record : records) {
 *         System.out.println("  Usage: " + record.getUsageDescription());
 *         System.out.println("  Selector: " + record.getSelectorDescription());
 *         System.out.println("  Matching: " + record.getMatchingTypeDescription());
 *     }
 * }
 * </pre>
 *
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - The DNS-Based Authentication of Named Entities (DANE)</a>
 * @see <a href="https://tools.ietf.org/html/rfc7671">RFC 7671 - The DNS-Based Authentication of Named Entities (DANE) Protocol: Updates and Operational Guidance</a>
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - SMTP Security via Opportunistic DNS-Based Authentication of Named Entities (DANE)</a>
 */
package com.mimecast.robin.mx.dane;
