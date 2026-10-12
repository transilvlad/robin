package com.mimecast.robin.trust;

import com.mimecast.robin.config.server.ServerConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrustManagerTest {
    @Test
    void emptyTruststoreUsesJvmCertificateAuthorities() throws Exception {
        TrustManager manager = new TrustManager(new ServerConfig(Map.of("truststore", "")));
        assertTrue(manager.getAcceptedIssuers().length > 0);
    }

    @Test
    void unreadableConfiguredTruststoreFailsExplicitly() {
        assertThrows(IOException.class, () -> new TrustManager(new ServerConfig(
                Map.of("truststore", "missing-truststore.jks"))));
    }
}
