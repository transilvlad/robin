/**
 * All things related to DNS, MX records, and MTA-STS.
 *
 * <p>This package contains a Java implementation of MTA-STS with support for TLSRPT record fetching.
 *
 * <p>SMTP MTA Strict Transport Security is designed to protect against the opportunistic nature of STARTTLS and MITM attacks
 * <br>that can remove STARTTLS advertising to force plain text exchange.
 *
 * <p>This is done by using a combination of DNS TXT record and well-known HTTPS text/plain file
 * <br>to enforce TLS 1.2 or newer to existing MX servers.
 *
 * <p>The key security functionality to defeat MITM attacks is the secure nature of certificates
 * <br>aside from known breaches of authorities.
 *
 * <p>The library does not provide a production ready trust manager or policy cache.
 * <br>A X509TrustManager implementation needs to be provided and should enable revocation checks.
 * <br>An abstract PolicyCache is provided to aid in integrating with your cloud cache.
 *
 * @see com.mimecast.robin.mx.StrictTransportSecurity
 */
package com.mimecast.robin.mx;

