package com.mimecast.robin.smtp.security;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.*;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standard TLS handshake negociation implementation.
 */
public class DefaultTLSSocket implements TLSSocket {
    private static final Logger log = LogManager.getLogger(DefaultTLSSocket.class);

    /**
     * Cached SSL contexts keyed by keystore configuration.
     * <p>Building an SSLContext requires loading the keystore from disk and initializing
     * key material; doing that per connection is expensive, so contexts are cached and
     * rebuilt only when the keystore configuration changes.
     */
    private static final Object CONTEXT_LOCK = new Object();
    private static volatile CachedContext serverContext;
    private static volatile SSLContext clientContext;

    /**
     * Server context cache entry with the keystore configuration it was built from.
     */
    private record CachedContext(SSLContext context, KeyManager[] keyManagers, String keyStorePath, String keyStoreType) {
    }

    /**
     * Socket instance.
     */
    private Socket socket;

    /**
     * Default TLS protocols supported as string array.
     */
    private String[] protocols;

    /**
     * Default TLS cipher suites supported as string array.
     */
    private String[] ciphers;

    /**
     * Security policy for this connection (DANE/MTA-STS/Opportunistic).
     */
    private SecurityPolicy securityPolicy;

    /**
     * Sets socket.
     *
     * @param socket Socket instance.
     * @return Self.
     */
    @Override
    public TLSSocket setSocket(Socket socket) {
        this.socket = socket;
        return this;
    }

    /**
     * Sets TLS protocols supported.
     *
     * @param protocols Protocols list.
     * @return Self.
     */
    @Override
    public TLSSocket setProtocols(String[] protocols) {
        if (protocols != null) {
            this.protocols = protocols;
        }
        return this;
    }

    /**
     * Sets TLS ciphers supported.
     *
     * @param ciphers Cipher suites list.
     * @return Self.
     */
    @Override
    public TLSSocket setCiphers(String[] ciphers) {
        if (ciphers != null) {
            this.ciphers = ciphers;
        }
        return this;
    }

    /**
     * Sets security policy for this connection.
     *
     * @param securityPolicy SecurityPolicy to enforce.
     * @return Self.
     */
    @Override
    public TLSSocket setSecurityPolicy(SecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
        return this;
    }

    /**
     * Enable encryption for the given socket.
     *
     * @param client True if in client mode.
     * @return SSLSocket instance.
     * @throws IOException              Unable to read.
     * @throws GeneralSecurityException Problems with TrustManager or KeyManager.
     */
    @Override
    public SSLSocket startTLS(boolean client) throws Exception {
        if (socket == null) {
            throw new IOException("Socket not defined");
        }

        // DANE requires a per-policy trust manager, so those contexts cannot be cached.
        SSLContext sc;
        if (securityPolicy != null && securityPolicy.isDane()) {
            log.info("Using DANE-aware trust manager for policy: {}", securityPolicy);
            TrustManager[] tm = new TrustManager[]{new DaneTrustManager(securityPolicy)};
            @SuppressWarnings("squid:S4423")
            SSLContext daneContext = SSLContext.getInstance("TLS");
            daneContext.init(client ? null : getServerContext().keyManagers(), tm, null);
            sc = daneContext;
        } else {
            sc = client ? getClientContext() : getServerContext().context();
        }
        SSLSocketFactory sf = sc.getSocketFactory();

        // Wrap 'socket' from above in a TLS socket.
        InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
        @SuppressWarnings("squid:S2095")
        SSLSocket sslSocket = (SSLSocket) sf.createSocket(socket, remoteAddress.getHostString(), socket.getPort(), true);

        // We are a client.
        sslSocket.setUseClientMode(client);

        // Allowed TLS protocols and supported cipher suites.
        sslSocket.setEnabledProtocols(getEnabledProtocols(sslSocket));
        sslSocket.setEnabledCipherSuites(getEnabledCipherSuites(sslSocket));

        // Make a friend!
        log.info("Attempting handshake with: {}.", remoteAddress.getHostString());
        sslSocket.startHandshake();
        log.debug("Handshake done with: {} / {}.", sslSocket.getSession().getProtocol(), sslSocket.getSession().getCipherSuite());

        return sslSocket;
    }

    /**
     * Gets default protocols or enabled ones from configured list.
     *
     * @param sslSocket SSLSocket instance.
     * @return Protocols list.
     */
    @Override
    public String[] getEnabledProtocols(SSLSocket sslSocket) {
        List<String> defaultProtocols = Arrays.asList(sslSocket.getEnabledProtocols());

        if (protocols != null && protocols.length > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Configured protocols: {}", String.join(", ", protocols));
            }

            List<String> supportedProtocols = new ArrayList<>();
            for (String protocol : protocols) {
                if (defaultProtocols.contains(protocol)) {
                    supportedProtocols.add(protocol);
                }
            }

            if (log.isTraceEnabled()) {
                log.trace("Supported protocols: {}", String.join(", ", supportedProtocols));
            }

            return supportedProtocols.toArray(new String[0]);
        }

        return defaultProtocols.toArray(new String[0]);
    }

    /**
     * Gets default cipher suites or enabled ones from configured list.
     *
     * @param sslSocket SSLSocket instance.
     * @return Cipher suites list.
     */
    @Override
    public String[] getEnabledCipherSuites(SSLSocket sslSocket) {
        List<String> defaultCipherSuites = Arrays.asList(sslSocket.getEnabledCipherSuites());

        if (ciphers != null && ciphers.length > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Configured cipher suites: {}", String.join(", ", ciphers));
            }

            List<String> supportedCipherSuites = new ArrayList<>();
            for (String cipherSuite : ciphers) {
                if (defaultCipherSuites.contains(cipherSuite)) {
                    supportedCipherSuites.add(cipherSuite);
                }
            }

            if (log.isTraceEnabled()) {
                log.trace("Supported cipher suites: {}", String.join(", ", supportedCipherSuites));
            }

            return supportedCipherSuites.toArray(new String[0]);
        }

        return defaultCipherSuites.toArray(new String[0]);
    }

    /**
     * Resets the cached SSL contexts.
     * <p>Must be called when the trust manager factory changes so new connections
     * pick up the new trust material.
     */
    public static void resetContextCache() {
        synchronized (CONTEXT_LOCK) {
            serverContext = null;
            clientContext = null;
        }
    }

    /**
     * Gets the cached server SSL context, rebuilding it if the keystore configuration changed.
     *
     * @return CachedContext instance.
     * @throws Exception Problems with TrustManager, KeyManager or keystore.
     */
    private CachedContext getServerContext() throws Exception {
        String path = Config.getProperties().getStringProperty("javax.net.ssl.keyStore");
        String storeType = Config.getProperties().getStringProperty("javax.net.ssl.keyStoreType", "JKS");

        CachedContext cached = serverContext;
        if (cached != null && StringUtils.equals(cached.keyStorePath(), path) && StringUtils.equals(cached.keyStoreType(), storeType)) {
            return cached;
        }

        synchronized (CONTEXT_LOCK) {
            cached = serverContext;
            if (cached != null && StringUtils.equals(cached.keyStorePath(), path) && StringUtils.equals(cached.keyStoreType(), storeType)) {
                return cached;
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            KeyStore ks = KeyStore.getInstance(storeType);
            ks.load(getKeyStore(), getKeyStorePassword());
            kmf.init(ks, getKeyStorePassword());
            KeyManager[] km = kmf.getKeyManagers();

            @SuppressWarnings("squid:S4423")
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(km, new TrustManager[]{Factories.getTrustManager()}, null);

            cached = new CachedContext(sc, km, path, storeType);
            serverContext = cached;
            return cached;
        }
    }

    /**
     * Gets the cached client SSL context with the default trust manager.
     *
     * @return SSLContext instance.
     * @throws Exception Problems with TrustManager.
     */
    private SSLContext getClientContext() throws Exception {
        SSLContext cached = clientContext;
        if (cached != null) {
            return cached;
        }

        synchronized (CONTEXT_LOCK) {
            if (clientContext == null) {
                @SuppressWarnings("squid:S4423")
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{Factories.getTrustManager()}, null);
                clientContext = sc;
            }
            return clientContext;
        }
    }

    /**
     * Gets keystore.
     *
     * @return Keystore.
     */
    private InputStream getKeyStore() {
        try {
            String path = Config.getProperties().getStringProperty("javax.net.ssl.keyStore");
            if (StringUtils.isNotBlank(path)) {
                return new BufferedInputStream(new java.io.FileInputStream(path), 8192);
            }
        } catch (FileNotFoundException e) {
            log.error("Error getting keystore.");
        }
        return null;
    }

    /**
     * Gets keystore password
     *
     * @return Password.
     */
    private char[] getKeyStorePassword() {
        String password = Config.getProperties().getStringProperty("javax.net.ssl.keyStorePassword");
        return StringUtils.isNotBlank(password) ? password.toCharArray() : "".toCharArray();
    }
}
