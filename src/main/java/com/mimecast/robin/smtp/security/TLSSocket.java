package com.mimecast.robin.smtp.security;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;

/**
 * TLS socket with DANE and MTA-STS support.
 * <p>Provides TLS negotiation with enforcement of security policies per RFC 7672 (DANE)
 * <br>and RFC 8461 (MTA-STS).
 */
public interface TLSSocket {

    /**
     * Sets socket.
     *
     * @param socket Socket instance.
     * @return Self.
     */
    TLSSocket setSocket(Socket socket);

    /**
     * Sets TLS protocols supported.
     *
     * @param protocols Protocols list.
     * @return Self.
     */
    TLSSocket setProtocols(String[] protocols);

    /**
     * Sets TLS ciphers supported.
     *
     * @param ciphers Cipher suites list.
     * @return Self.
     */
    TLSSocket setCiphers(String[] ciphers);

    /**
     * Sets security policy for this connection.
     * <p>The security policy determines:
     * <ul>
     *   <li>Whether TLS is mandatory or opportunistic</li>
     *   <li>Certificate validation requirements (PKI vs DANE TLSA)</li>
     *   <li>Failure handling (reject vs fall back)</li>
     * </ul>
     *
     * @param securityPolicy SecurityPolicy to enforce.
     * @return Self.
     */
    TLSSocket setSecurityPolicy(SecurityPolicy securityPolicy);

    /**
     * Enable encryption for the given socket.
     *
     * @param client True if in client mode.
     * @return SSLSocket instance.
     * @throws IOException              Unable to read.
     * @throws GeneralSecurityException Problems with TrustManager or KeyManager.
     */
    SSLSocket startTLS(boolean client) throws Exception;

    /**
     * Gets default protocols or enabled ones from configured list.
     *
     * @param sslSocket SSLSocket instance.
     * @return Protocols list.
     */
    String[] getEnabledProtocols(SSLSocket sslSocket);

    /**
     * Gets default cipher suites or enabled ones from configured list.
     *
     * @param sslSocket SSLSocket instance.
     * @return Cipher suites list.
     */
    String[] getEnabledCipherSuites(SSLSocket sslSocket);
}
