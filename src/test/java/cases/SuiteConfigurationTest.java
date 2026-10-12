package cases;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SuiteConfigurationTest {
    @TempDir
    Path dir;

    @Test
    void demoBaselineUsesBundledTlsButNoTestProtocolFeatures() throws Exception {
        Path secrets = dir.resolve("properties");
        Files.writeString(secrets, "MAIL_HOSTNAME=mail.example.test\nMTA_DB_PASSWORD=test-only\nMAILBOX_DB_PASSWORD=test-only\n");
        String previous = System.getProperty("secrets.file");
        System.setProperty("secrets.file", secrets.toString());
        try {
            ServerConfig server = new ServerConfig(".suite/cfg/server.json5");
            assertEquals("mail.example.test", server.getHostname());
            assertFalse(server.isChaosHeaders());
            assertFalse(server.isXclientEnabled());
            assertFalse(server.isRenameHeaderEnabled());
            assertTrue(server.isAllowSelfSigned());
            assertEquals("", server.getPemCertPath());
            assertEquals("", server.getPemKeyPath());
            assertEquals("/usr/local/robin/keystore.jks", server.getKeyStore());
            assertEquals("avengers", server.getKeyStorePassword());
            assertTrue(server.isAuth());
            assertTrue(server.isStartTls());
        } finally {
            if (previous == null) {
                System.clearProperty("secrets.file");
            } else {
                System.setProperty("secrets.file", previous);
            }
        }
    }

    @Test
    void productionTemplateUsesOperatorPemAndRealDelivery() throws Exception {
        Path secrets = dir.resolve("properties");
        Files.writeString(secrets, "MAIL_HOSTNAME=mail.example.test\nMTA_DB_PASSWORD=test-only\nMAILBOX_DB_PASSWORD=test-only\n");
        String previous = System.getProperty("secrets.file");
        System.setProperty("secrets.file", secrets.toString());
        try {
            ServerConfig productionServer = new ServerConfig(".suite/cfg-production/server.json5");
            assertEquals("mail.example.test", productionServer.getHostname());
            assertFalse(productionServer.isAllowSelfSigned());
            assertEquals("", productionServer.getTrustStore());
            assertEquals("/run/robin-tls/fullchain.pem", productionServer.getPemCertPath());
            assertEquals("/run/robin-tls/privkey.pem", productionServer.getPemKeyPath());
            assertEquals(25, productionServer.getSmtpPort());
            assertEquals(465, productionServer.getSecurePort());
            assertEquals(587, productionServer.getSubmissionPort());
            assertTrue(productionServer.isAuth());
            assertTrue(productionServer.isStartTls());

            ServerConfig baseServer = new ServerConfig(".suite/cfg/server.json5");
            assertFalse(baseServer.getUsers().isListEnabled());
            assertTrue(baseServer.getDovecot().isAuthSqlEnabled());
            assertTrue(baseServer.getDovecot().isUserLookup());
            assertTrue(baseServer.getClamAV().getBooleanProperty("enabled"));
            assertTrue(baseServer.getRspamd().isEnabled());
            assertFalse(baseServer.getRspamd().isDiscardEnabled());
            assertTrue(baseServer.getRelay().getBooleanProperty("enabled"));
            assertFalse(baseServer.getRelay().getBooleanProperty("inboundEnabled"));
            assertTrue(baseServer.getRelay().getBooleanProperty("outboundEnabled"));
            assertTrue(baseServer.getRelay().getBooleanProperty("outboundMxEnabled"));
            assertFalse(baseServer.getStorage().getBooleanProperty("localMailbox"));
            assertTrue(baseServer.getStorage().getBooleanProperty("disableRenameHeader"));
            assertFalse(new BasicConfig(".suite/cfg/bots.json5").getListProperty("bots").isEmpty());
            assertTrue(new BasicConfig(".suite/cfg-production/bots.json5").getListProperty("bots").isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("secrets.file");
            } else {
                System.setProperty("secrets.file", previous);
            }
        }
    }
}
