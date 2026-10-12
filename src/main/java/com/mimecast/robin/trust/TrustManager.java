package com.mimecast.robin.trust;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Custom X509TrustManager implementation for production use.
 * This trust manager validates certificates against a configured store, or the JVM's
 * CA roots when the configured path is empty.
 * It ensures that only certificates issued by trusted Certificate Authorities (CAs) are accepted.
 */
public class TrustManager implements X509TrustManager {
    private static final Logger log = LogManager.getLogger(TrustManager.class);

    private final X509TrustManager defaultTrustManager;

    /**
     * Constructor that initializes the trust manager with a trust store.
     *
     * @throws Exception If the trust store cannot be loaded or initialized.
     */
    public TrustManager() throws Exception {
        this(Config.getServer());
    }

    TrustManager(ServerConfig config) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = null;
        if (!config.getTrustStore().isEmpty()) {
            // Try to read trust store password from file.
            String trustStorePassword;
            try {
                trustStorePassword = new String(Files.readAllBytes(Paths.get(config.getTrustStorePassword())));
            } catch (IOException e) {
                log.warn("Truststore password treated as text.");
                trustStorePassword = config.getTrustStorePassword();
            }

            // Load the trust store from the specified file.
            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (BufferedInputStream fis = new BufferedInputStream(Files.newInputStream(Paths.get(config.getTrustStore())), 8192)) {
                trustStore.load(fis, trustStorePassword.toCharArray());
            }
        }

        // A null store selects the JVM's configured/default CA roots.
        tmf.init(trustStore);

        // Find the X509TrustManager from the factory
        X509TrustManager x509Tm = null;
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                x509Tm = (X509TrustManager) tm;
                break;
            }
        }
        if (x509Tm == null) {
            throw new Exception("No X509TrustManager found");
        }
        this.defaultTrustManager = x509Tm;
    }

    /**
     * Validates the client's certificate chain.
     * This method checks if the client's certificate is trusted based on the configured trust store.
     *
     * @param chain    The certificate chain to validate.
     * @param authType The authentication type (e.g., "RSA").
     * @throws CertificateException If the certificate chain is not trusted.
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        defaultTrustManager.checkClientTrusted(chain, authType);
    }

    /**
     * Validates the server's certificate chain.
     * This method checks if the server's certificate is trusted based on the configured trust store.
     *
     * @param chain    The certificate chain to validate.
     * @param authType The authentication type (e.g., "RSA").
     * @throws CertificateException If the certificate chain is not trusted.
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        defaultTrustManager.checkServerTrusted(chain, authType);
    }

    /**
     * Returns the list of accepted issuers (trusted CAs).
     *
     * @return Array of X509Certificate representing the accepted issuers.
     */
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return defaultTrustManager.getAcceptedIssuers();
    }
}
