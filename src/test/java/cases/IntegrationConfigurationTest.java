package cases;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.client.ClientConfig;
import com.mimecast.robin.config.server.ServerConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationConfigurationTest {

    @Test
    void serverFixturesEnableRequiredTestFeatures() throws Exception {
        ServerConfig server = new ServerConfig(".integration/cfg/server.json5");
        assertTrue(server.isChaosHeaders());
        assertTrue(server.isXclientEnabled());
        assertFalse(server.isRenameHeaderEnabled());
        assertTrue(server.isAllowSelfSigned());
        assertTrue(server.getDovecot().isAuthSqlEnabled());
        assertTrue(server.getDovecot().isUserLookup());
        assertTrue(server.getClamAV().getBooleanProperty("enabled"));
        assertTrue(server.getRspamd().isEnabled());
        assertTrue(new BasicConfig(server.getQueue().getMapProperty("queuePgSQL")).getBooleanProperty("enabled"));
        assertFalse(new BasicConfig(".integration/cfg/config.json5").getBooleanProperty("enabled"));
    }

    @Test
    void clientFixtureIsIndependentOfDevelopmentConfiguration() throws Exception {
        ClientConfig client = new ClientConfig(".integration/client-cfg/client.json5");
        assertEquals(2525L, client.getLongProperty("port"));
        assertNotNull(client.getRoute("docker"));
        assertEquals(2525L, client.getRoute("docker").getLongProperty("port"));
        ServerConfig server = new ServerConfig(".integration/client-cfg/server.json5");
        assertTrue(server.isAllowSelfSigned());
        assertEquals(0, server.getSmtpPort());
        assertEquals(0, server.getSecurePort());
        assertEquals(0, server.getSubmissionPort());
        assertTrue(Files.isRegularFile(Path.of(".integration/client-cfg/routes.json5")));
    }
}
